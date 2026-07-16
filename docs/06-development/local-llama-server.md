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
  --port 8080 \
  -c 8192 \
  --alias local-model
```

接口：

```text
http://localhost:8080/v1/chat/completions
```

Android 模拟器访问宿主机：

```text
http://10.0.2.2:8080/v1
```

Android 真机使用开发机局域网 IP，并确保防火墙允许访问。

## 3. Android Debug 配置

Debug 构建允许选择：

- Mock Chat Provider；
- Local OpenAI-compatible Provider；
- Cloud Provider。

本地服务可配置一个无意义测试 key，因为部分客户端会统一要求 Authorization；服务器不需要时不要把真实云端 Key填入。

## 4. 明文 HTTP

仅 Debug 允许对局域网本地服务器使用明文 HTTP，并通过 Network Security Config 限定开发地址。Release 必须默认 HTTPS。

## 5. 验证

```bash
curl http://localhost:8080/v1/chat/completions \
  -H 'Content-Type: application/json' \
  -d '{
    "model": "local-model",
    "messages": [{"role":"user","content":"请用一句话问候老人"}],
    "stream": false
  }'
```

## 6. 限制

- 小模型的 Tool Calling 和中文稳定性可能不足；
- 本地响应速度取决于硬件；
- llama-server 与云模型能力不完全一致；
- 不应只在本地模型上验证支付、SOS 等安全策略，Policy Engine 必须独立测试。
