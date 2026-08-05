package com.example.silverageassistant.data.gui

import android.util.Base64
import com.example.silverageassistant.data.model.ModelApiCredentialStore
import com.example.silverageassistant.data.model.ModelConfigurationStore
import com.example.silverageassistant.data.model.ModelRuntimeConfiguration
import com.example.silverageassistant.data.model.OpenAiCompatibleDialect
import com.example.silverageassistant.data.usage.AgentUsageScope
import com.example.silverageassistant.data.usage.ModelUsageRecorder
import com.example.silverageassistant.domain.agent.SystemPromptProvider
import com.example.silverageassistant.domain.gui.GuiDeviceAction
import com.example.silverageassistant.domain.gui.GuiConfirmationScope
import com.example.silverageassistant.domain.gui.GuiDebugTrace
import com.example.silverageassistant.domain.gui.GuiGroundingMode
import com.example.silverageassistant.domain.gui.GuiPlannedAction
import com.example.silverageassistant.domain.gui.GuiPlanningRequest
import com.example.silverageassistant.domain.gui.GuiScrollDirection
import com.example.silverageassistant.domain.gui.GuiVisionPlanner
import com.example.silverageassistant.domain.gui.vision.ModelCoordinateSpace
import com.example.silverageassistant.domain.gui.vision.ModelPointPrediction
import com.example.silverageassistant.domain.gui.vision.PointD
import com.example.silverageassistant.domain.model.ChatUsage
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * GUI Agent 独立的视觉 Planner。它复用模型连接配置与凭证，但不复用聊天 Agent 的上下文；
 * 每个 GuiRun 的短期历史由执行器显式传入，截图和节点不会持久化。
 */
class OpenAiGuiVisionPlanner(
    private val configurationStore: ModelConfigurationStore,
    private val credentialStore: ModelApiCredentialStore,
    private val systemPromptProvider: SystemPromptProvider,
    private val usageRecorder: ModelUsageRecorder? = null,
    private val groundingModeProvider: () -> GuiGroundingMode = {
        GuiGroundingMode.HYBRID_NODE_FIRST
    },
    private val client: OkHttpClient = OkHttpClient(),
    private val clock: () -> Long = System::currentTimeMillis,
) : GuiVisionPlanner {
    override suspend fun plan(request: GuiPlanningRequest): GuiPlannedAction =
        withContext(Dispatchers.IO) {
            val configuration = configurationStore.configuration.value
            val groundingMode = groundingModeProvider()
            val startedAt = clock()
            var successful = false
            var usage = ChatUsage()
            try {
                val serviceConfig = configuration.toServiceConfig()
                GuiDebugTrace.record(
                    source = "mllm",
                    stage = "request",
                    message = "发送 GUI 第 ${request.step} 步截图",
                    details = buildString {
                        appendLine("model=${configuration.model}")
                        appendLine(
                            "timeout=${serviceConfig.connectTimeoutSeconds}s/" +
                                "${serviceConfig.readTimeoutSeconds}s",
                        )
                        appendLine("frame=${request.observation.geometry.frameId}")
                        appendLine(
                            "image=${request.observation.geometry.modelImage.uploadSize.width}x" +
                                "${request.observation.geometry.modelImage.uploadSize.height}",
                        )
                        appendLine("bytes=${request.observation.uploadBytes.size}")
                        appendLine("grounding=${groundingMode.name}")
                        appendLine("observed_nodes=${request.observation.nodes.size}")
                        appendLine(
                            "model_nodes=" + if (
                                groundingMode == GuiGroundingMode.COORDINATE_ONLY
                            ) 0 else request.observation.nodes.size,
                        )
                        append("task=${request.task}")
                    },
                )
                val payload = buildRequest(configuration, request, groundingMode)
                val httpRequest = Request.Builder()
                    .url(serviceConfig.chatCompletionsUrl)
                    .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
                    .apply {
                        credentialStore.loadApiKey()?.takeIf(String::isNotBlank)?.let {
                            header("Authorization", "Bearer $it")
                        }
                    }
                    .build()
                val requestClient = client.newBuilder()
                    .connectTimeout(serviceConfig.connectTimeoutSeconds, TimeUnit.SECONDS)
                    .readTimeout(serviceConfig.readTimeoutSeconds, TimeUnit.SECONDS)
                    .build()
                val responseJson = requestClient.newCall(httpRequest).execute().use { response ->
                    if (!response.isSuccessful) {
                        throw IOException("GUI MLLM HTTP ${response.code}")
                    }
                    JSONObject(
                        response.body?.string()
                            ?: throw IOException("GUI MLLM 返回为空"),
                    )
                }
                usage = responseJson.optJSONObject("usage")?.let {
                    ChatUsage(
                        promptTokens = it.optLongOrNull("prompt_tokens"),
                        completionTokens = it.optLongOrNull("completion_tokens"),
                        totalTokens = it.optLongOrNull("total_tokens"),
                    )
                } ?: ChatUsage()
                val content = responseJson.getJSONArray("choices")
                    .getJSONObject(0)
                    .getJSONObject("message")
                    .get("content")
                    .let(::readContent)
                GuiDebugTrace.record(
                    source = "mllm",
                    stage = "raw_response",
                    message = "收到 GUI 模型原始回复",
                    details = content,
                )
                parseAction(content, request, groundingMode).also { successful = true }
            } catch (error: Exception) {
                GuiDebugTrace.record(
                    source = "mllm",
                    stage = "error",
                    message = error.message ?: "GUI 模型请求或解析失败",
                    details = error::class.java.simpleName,
                )
                throw error
            } finally {
                runCatching {
                    usageRecorder?.recordMllm(
                        provider = "openai_compatible",
                        model = configuration.model,
                        feature = AgentUsageScope.GUI_AGENT.feature,
                        startedAtEpochMillis = startedAt,
                        finishedAtEpochMillis = clock(),
                        usage = usage,
                        estimated = usage.promptTokens == null ||
                            usage.completionTokens == null,
                        successful = successful,
                    )
                }
            }
        }

    private suspend fun buildRequest(
        configuration: ModelRuntimeConfiguration,
        request: GuiPlanningRequest,
        groundingMode: GuiGroundingMode,
    ): JSONObject {
        val observation = request.observation
        val frame = observation.geometry
        val dataUrl = "data:${observation.mimeType};base64," +
            Base64.encodeToString(observation.uploadBytes, Base64.NO_WRAP)
        val tools = request.sharedTools.definitions.map { definition ->
            JSONObject()
                .put("name", definition.name)
                .put("description", definition.description)
                .put("parameters", JSONObject(definition.parametersJson))
        }
        val nodes = observation.nodes
            .takeIf { groundingMode == GuiGroundingMode.HYBRID_NODE_FIRST }
            .orEmpty()
            .map { node ->
            val normalizedBounds = node.boundsInScreen.let { bounds ->
                val screenToCapture = frame.captureToScreen.inverse()
                val topLeft = frame.modelImage.captureToUpload.map(
                    screenToCapture.map(PointD(bounds.left, bounds.top)),
                )
                val bottomRight = frame.modelImage.captureToUpload.map(
                    screenToCapture.map(PointD(bounds.right, bounds.bottom)),
                )
                val uploadWidth = frame.modelImage.uploadSize.width.toDouble()
                val uploadHeight = frame.modelImage.uploadSize.height.toDouble()
                listOf(
                    (topLeft.x / uploadWidth * 1000.0).toInt().coerceIn(0, 1000),
                    (topLeft.y / uploadHeight * 1000.0).toInt().coerceIn(0, 1000),
                    (bottomRight.x / uploadWidth * 1000.0).toInt().coerceIn(0, 1000),
                    (bottomRight.y / uploadHeight * 1000.0).toInt().coerceIn(0, 1000),
                )
            }
            JSONObject()
                .put("id", node.nodeId)
                .put("text", node.text ?: JSONObject.NULL)
                .put("description", node.contentDescription ?: JSONObject.NULL)
                .put("class", node.className ?: JSONObject.NULL)
                .put("view_id", node.viewId ?: JSONObject.NULL)
                .put(
                    "bounds_normalized_0_1000",
                    JSONArray(normalizedBounds),
                )
                .put("clickable", node.clickable)
                .put("editable", node.editable)
                .put("scrollable", node.scrollable)
                .put("password", node.password)
        }
        val history = request.history.takeLast(MAX_HISTORY_STEPS).map {
            JSONObject()
                .put("action", it.actionSummary)
                .put("result", it.resultSummary)
        }
        val stateText = JSONObject()
            .put("task", request.task)
            .put("run_attempt", request.attempt)
            .put("step", request.step)
            .put("frame_id", frame.frameId)
            .put(
                "uploaded_image_size",
                JSONObject()
                    .put("width", frame.modelImage.uploadSize.width)
                    .put("height", frame.modelImage.uploadSize.height),
            )
            .put("target_package", observation.targetPackage)
            .put("window_title", observation.windowTitle ?: JSONObject.NULL)
            .put("grounding_mode", groundingMode.name.lowercase())
            .put("recent_steps", JSONArray(history))
            .put("shared_tools", JSONArray(tools))
            .apply {
                if (groundingMode == GuiGroundingMode.HYBRID_NODE_FIRST) {
                    put("accessibility_nodes", JSONArray(nodes))
                }
            }
            .toString()
        val userContent = JSONArray()
            .put(
                JSONObject()
                    .put("type", "text")
                    .put(
                        "text",
                            "这是当前唯一有效的屏幕帧，也是判断页面状态的唯一事实。\n" +
                            "状态如下：\n$stateText\n" +
                            if (groundingMode == GuiGroundingMode.COORDINATE_ONLY) {
                                "本轮是纯坐标实验，只根据截图选择一个最小下一步动作；" +
                                    "不得生成 node_id，不要推测后续页面，只返回规定 JSON。"
                            } else {
                                "只根据当前截图和节点选择一个最小下一步动作；不要推测后续页面，" +
                                    "只返回规定 JSON。"
                            },
                    ),
            )
            .put(
                JSONObject()
                    .put("type", "image_url")
                    .put("image_url", JSONObject().put("url", dataUrl)),
            )
        val messages = JSONArray()
            .put(
                JSONObject()
                    .put("role", "system")
                    .put(
                        "content",
                        systemPromptProvider.systemPrompt() + "\n\n" +
                            actionProtocol(groundingMode),
                    ),
            )
            .put(JSONObject().put("role", "user").put("content", userContent))
        return JSONObject()
            .put("model", configuration.model)
            .put("stream", false)
            .put("temperature", minOf(configuration.temperature, 0.2))
            .put("top_p", configuration.topP)
            .put("max_tokens", minOf(configuration.maxOutputTokens, 512))
            .put("response_format", JSONObject().put("type", "json_object"))
            .put("messages", messages)
            .also { root ->
                if (configuration.dialect == OpenAiCompatibleDialect.LlamaCpp) {
                    root.put("top_k", configuration.topK)
                    root.put(
                        "chat_template_kwargs",
                        JSONObject().put("enable_thinking", false),
                    )
                }
            }
    }

    private fun parseAction(
        raw: String,
        request: GuiPlanningRequest,
        groundingMode: GuiGroundingMode,
    ): GuiPlannedAction {
        val cleaned = raw.trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
        val root = JSONObject(cleaned)
        val action = root.getString("action").trim().lowercase()
        val reason = root.optString("reason").trim().take(MAX_MESSAGE_LENGTH)
        fun frameId(): String = root.getString("frame_id").also {
            require(it == request.observation.geometry.frameId) {
                "模型动作引用了过期截图"
            }
        }
        return when (action) {
            "click_node" -> {
                require(groundingMode == GuiGroundingMode.HYBRID_NODE_FIRST) {
                    "纯坐标实验禁止使用 click_node"
                }
                GuiPlannedAction.Device(
                    action = GuiDeviceAction.ClickNode(
                        frameId = frameId(),
                        nodeId = root.getString("node_id"),
                    ),
                    summary = reason.ifBlank { "点击页面控件" },
                )
            }

            "click_point" -> {
                val currentFrameId = frameId()
                val (x, y) = root.readPointCoordinates()
                GuiPlannedAction.Device(
                    action = GuiDeviceAction.ClickPoint(
                        frameId = currentFrameId,
                        point = ModelPointPrediction(
                            frameId = currentFrameId,
                            x = x,
                            y = y,
                            coordinateSpace = ModelCoordinateSpace.NORMALIZED_0_1000,
                        ),
                    ),
                    summary = reason.ifBlank { "点击屏幕位置" },
                )
            }

            "input_text" -> {
                require(groundingMode == GuiGroundingMode.HYBRID_NODE_FIRST) {
                    "纯坐标实验禁止使用带 node_id 的 input_text"
                }
                GuiPlannedAction.Device(
                    action = GuiDeviceAction.InputText(
                        frameId = frameId(),
                        nodeId = root.getString("node_id"),
                        text = root.getString("text").take(MAX_INPUT_LENGTH),
                    ),
                    summary = reason.ifBlank { "输入文字" },
                )
            }

            "input_text_focused" -> {
                require(groundingMode == GuiGroundingMode.COORDINATE_ONLY) {
                    "input_text_focused 只用于纯坐标实验"
                }
                GuiPlannedAction.Device(
                    action = GuiDeviceAction.InputTextFocused(
                        frameId = frameId(),
                        text = root.getString("text").take(MAX_INPUT_LENGTH),
                    ),
                    summary = reason.ifBlank { "向已聚焦输入框输入文字" },
                )
            }

            "scroll" -> GuiPlannedAction.Device(
                action = GuiDeviceAction.Scroll(
                    frameId = frameId(),
                    nodeId = if (groundingMode == GuiGroundingMode.HYBRID_NODE_FIRST) {
                        root.optString("node_id").takeIf(String::isNotBlank)
                    } else {
                        null
                    },
                    direction = when (root.optString("direction").lowercase()) {
                        "backward", "up" -> GuiScrollDirection.BACKWARD
                        else -> GuiScrollDirection.FORWARD
                    },
                ),
                summary = reason.ifBlank { "滚动页面" },
            )

            "back" -> GuiPlannedAction.Device(
                action = GuiDeviceAction.Back(frameId()),
                summary = reason.ifBlank { "返回上一页" },
            )

            "wait" -> GuiPlannedAction.Wait(
                milliseconds = root.optLong("milliseconds", DEFAULT_WAIT_MILLIS)
                    .coerceIn(MIN_WAIT_MILLIS, MAX_WAIT_MILLIS),
                reason = reason.ifBlank { "等待页面加载" },
            )

            "ask_elder" -> GuiPlannedAction.AskElder(
                message = root.getString("message").take(MAX_MESSAGE_LENGTH),
                confirmationScope = when (
                    root.optString("confirmation_scope").trim().lowercase()
                ) {
                    "order_submission" -> GuiConfirmationScope.ORDER_SUBMISSION
                    else -> GuiConfirmationScope.GENERAL
                },
            )

            "ready_for_payment" -> GuiPlannedAction.ReadyForPayment(
                root.getString("message").take(MAX_MESSAGE_LENGTH),
            )

            "use_tool" -> GuiPlannedAction.UseTool(
                toolName = root.getString("tool_name"),
                argumentsJson = root.optJSONObject("arguments")?.toString() ?: "{}",
                reason = reason.ifBlank { "调用共享工具" },
            )

            "complete" -> GuiPlannedAction.Complete(
                root.optString("summary").ifBlank { "任务已完成" }.take(MAX_MESSAGE_LENGTH),
            )

            "fail" -> GuiPlannedAction.Fail(
                root.optString("message").ifBlank { "无法继续完成任务" }
                    .take(MAX_MESSAGE_LENGTH),
            )

            else -> error("无法识别 GUI 动作：$action")
        }
    }

    private fun JSONObject.readPointCoordinates(): Pair<Double, Double> {
        val array = when {
            opt("x") is JSONArray && !has("y") -> optJSONArray("x")
            optJSONArray("point") != null -> optJSONArray("point")
            optJSONArray("coordinates") != null -> optJSONArray("coordinates")
            else -> null
        }
        val coordinates = if (array != null) {
            require(array.length() == 2) { "坐标数组必须只包含 x、y 两个数字" }
            GuiDebugTrace.record(
                source = "mllm",
                stage = "coordinate_json_normalized",
                message = "已兼容模型返回的坐标数组并转换为 x/y",
                details = array.toString(),
            )
            array.getDouble(0) to array.getDouble(1)
        } else {
            getDouble("x") to getDouble("y")
        }
        require(coordinates.first.isFinite() && coordinates.second.isFinite()) {
            "模型坐标必须是有限数字"
        }
        require(coordinates.first in 0.0..1000.0 && coordinates.second in 0.0..1000.0) {
            "模型归一化坐标必须位于 0..1000"
        }
        return coordinates
    }

    private fun actionProtocol(mode: GuiGroundingMode): String = when (mode) {
        GuiGroundingMode.HYBRID_NODE_FIRST -> HYBRID_ACTION_PROTOCOL
        GuiGroundingMode.COORDINATE_ONLY -> COORDINATE_ACTION_PROTOCOL
    }

    private fun readContent(value: Any): String = when (value) {
        is String -> value
        is JSONArray -> buildString {
            for (index in 0 until value.length()) {
                val item = value.optJSONObject(index) ?: continue
                if (item.optString("type") == "text") append(item.optString("text"))
            }
        }
        else -> value.toString()
    }

    private fun JSONObject.optLongOrNull(name: String): Long? =
        if (has(name) && !isNull(name)) getLong(name) else null

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        const val MAX_HISTORY_STEPS = 8
        const val MAX_INPUT_LENGTH = 200
        const val MAX_MESSAGE_LENGTH = 200
        const val DEFAULT_WAIT_MILLIS = 1_000L
        const val MIN_WAIT_MILLIS = 250L
        const val MAX_WAIT_MILLIS = 3_000L
        val HYBRID_ACTION_PROTOCOL = """
            【动作输出协议】
            每次只能选择一个动作并只返回一个 JSON 对象。
            截图和 accessibility_nodes 是唯一事实；recent_steps 只记录历史，不能证明当前页面。
            禁止编造看不见的按钮、页面、商品、地址、金额、订单状态或完成结果。
            必须严格执行 task 的原始范围，不得把“点外卖”扩展成选餐或下单。
            优先使用 accessibility_nodes 中的节点，不得猜测不存在的 node_id。
            所有页面动作必须原样回显当前 frame_id；坐标是相对上传图片的 0..1000。
            click_point 的 x 和 y 必须分别是单个 JSON 数字，禁止数组、字符串或遗漏 y。
            可用动作：
            {"action":"click_node","frame_id":"...","node_id":"0.1","reason":"..."}
            {"action":"click_point","frame_id":"...","x":0到1000,"y":0到1000,"reason":"仅在无可用节点时使用"}
            {"action":"input_text","frame_id":"...","node_id":"...","text":"...","reason":"..."}
            {"action":"scroll","frame_id":"...","node_id":"可选","direction":"forward或backward","reason":"..."}
            {"action":"back","frame_id":"...","reason":"..."}
            {"action":"wait","milliseconds":250到3000,"reason":"..."}
            {"action":"ask_elder","message":"需要老人明确确认或补充的一个问题","confirmation_scope":"general或order_submission"}
            {"action":"ready_for_payment","message":"说明订单和金额，请老人亲自付款"}
            {"action":"use_tool","tool_name":"共享工具名称","arguments":{},"reason":"..."}
            {"action":"complete","summary":"当前新截图中可直接看见、且符合原始 task 的完成证据"}
            {"action":"fail","message":"确实无法继续的原因"}
            禁止点击支付确认、提交订单或任何包含付款含义的按钮；到达付款步骤必须返回
            ready_for_payment。点击“提交订单/确认下单”前必须先返回 confirmation_scope 为
            order_submission 的 ask_elder；只有最近步骤明确显示老人已确认后才能点击提交订单。
            当前画面没有出现必须由老人决定的选项时，禁止提前 ask_elder，继续执行页面内最小动作。
        """.trimIndent()
        val COORDINATE_ACTION_PROTOCOL = """
            【纯坐标实验动作输出协议】
            每次只能选择一个动作并只返回一个 JSON 对象。
            当前上传截图是唯一的目标定位事实；请求中不会提供 accessibility_nodes。
            禁止生成 click_node、input_text、node_id，禁止根据历史猜测旧坐标。
            必须严格执行 task 的原始范围，不得编造看不见的按钮、页面、商品、价格或结果。
            所有页面动作必须原样回显当前 frame_id；x、y 必须是相对于本次实际上传图片的
            0..1000 归一化坐标，左上角为 (0,0)，右下角接近 (1000,1000)。
            click_point 的 x 和 y 必须分别是单个 JSON 数字，禁止数组、字符串或遗漏 y。
            可用动作：
            {"action":"click_point","frame_id":"...","x":0到1000,"y":0到1000,"reason":"当前截图中的可见目标"}
            {"action":"input_text_focused","frame_id":"...","text":"...","reason":"仅在上一步已用坐标点击并聚焦普通输入框后使用"}
            {"action":"scroll","frame_id":"...","direction":"forward或backward","reason":"..."}
            {"action":"back","frame_id":"...","reason":"..."}
            {"action":"wait","milliseconds":250到3000,"reason":"..."}
            {"action":"ask_elder","message":"需要老人明确确认或补充的一个问题","confirmation_scope":"general或order_submission"}
            {"action":"ready_for_payment","message":"说明订单和金额，请老人亲自付款"}
            {"action":"use_tool","tool_name":"共享工具名称","arguments":{},"reason":"..."}
            {"action":"complete","summary":"当前新截图中可直接看见、且符合原始 task 的完成证据"}
            {"action":"fail","message":"确实无法继续的原因"}
            禁止点击支付确认、提交订单或任何包含付款含义的按钮；到达付款步骤必须返回
            ready_for_payment。点击“提交订单/确认下单”前必须先返回 confirmation_scope 为
            order_submission 的 ask_elder；只有最近步骤明确显示老人已确认后才能点击提交订单。
            当前画面没有出现必须由老人决定的选项时，禁止提前 ask_elder，继续执行页面内最小动作。
        """.trimIndent()
    }
}
