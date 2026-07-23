# 本地 llama-server 开发说明

## 1. 用途

在尚未接入真实云端模型或不希望消耗费用时，用本地 `llama-server` 模拟 OpenAI 兼容聊天接口。它主要验证：

- Android 网络链路；
- Chat Completions 请求；
- 流式文本；
- Tool Call 解析（取决于模型模板）；
- Agent 状态机。

ASR、TTS 和图像能力可先使用 Mock Provider；本地服务器是否支持多模态取决于模型与版本。

## 2. 启动示例

安装/编译 llama.cpp 后：

```bash
llama-server \
  -m /absolute/path/to/model.gguf \
  --host 0.0.0.0 \
  --port 11435 \
  -c 8192 \
  --alias qwen3_5 \
  --jinja
```

接口：

```text
http://58.199.163.98:11435/v1/chat/completions
```

当前 Android Debug 默认访问：

```text
http://58.199.163.98:11435
```

模拟器和真机都需要能路由到该局域网地址，并确保服务监听 `0.0.0.0`、防火墙允许访问。若服务实际运行在模拟器宿主机，也可在未入库的 `AndroidAgent/dev.properties` 中覆盖为 `http://10.0.2.2:11435`。

## 3. Android Debug 配置

Debug 构建使用 OpenAI-compatible Provider，默认模型为 `qwen3_5`。可在 `AndroidAgent/dev.properties` 中覆盖：

```properties
modelBaseUrl=http://58.199.163.98:11435
chatModel=qwen3_5
```

本地服务没有启用 API Key 时，Android 不发送 `Authorization`。正式云端服务的 Key 由老人设备本地加密凭证存储提供，不写入此配置文件。

## 4. 明文 HTTP

仅 Debug 允许对局域网本地服务器使用明文 HTTP，并通过 Network Security Config 限定开发地址。Release 必须默认 HTTPS。

## 5. 验证

```bash
curl http://58.199.163.98:11435/v1/chat/completions \
  -H 'Content-Type: application/json' \
  -d '{
    "model": "qwen3_5",
    "messages": [{"role":"user","content":"现在几点？"}],
    "tools": [{
      "type": "function",
      "function": {
        "name": "get_current_time",
        "description": "读取老人设备的当前日期、时间、星期和时区",
        "parameters": {"type":"object","properties":{},"additionalProperties":false}
      }
    }],
    "temperature": 0.6,
    "top_p": 0.9,
    "top_k": 40,
    "chat_template_kwargs": {"enable_thinking": false},
    "stream": true
  }'
```

## 6. 限制

- 小模型的 Tool Calling 和中文稳定性可能不足；
- 本地响应速度取决于硬件；
- llama-server 与云模型能力不完全一致；
- 不应只在本地模型上验证支付、SOS 等安全策略，Policy Engine 必须独立测试。
