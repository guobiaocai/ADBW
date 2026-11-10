# Relay 部署（一次性 ~1 分钟）

```bash
cd relay
npm install
npx wrangler deploy
```

完成。没有 KV、没有 secret——密码鉴权是端到端的（手机侧校验），边缘不接触任何秘密。

部署成功后记下输出的域名（如 `adbw-relay.<account>.workers.dev`），
连同 App 里显示的 deviceId 和密码组成配对串：

```
vibeadb://<relay-host>/<deviceId>#<password>
```

配对串**永久有效**（中继域名恒定，不再有 URL 轮换），粘给 MCP 服务器即可。

## 免费额度说明

- Durable Objects 免费计划（SQLite 后端）：~100k requests/day。
- 每条 WebSocket 消息计 1 request；个人短时测试会话用量远低于此。
- 若被扫描/滥用导致额度异常，Cloudflare 控制台可看用量，届时再考虑加边缘限流。
