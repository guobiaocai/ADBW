package com.adbw.app.shizuku;

interface IGatewayService {
    /**
     * 启动网关。
     * mode = "relay"：出站连接边缘中继 wss://relayHost/device。
     * mode = "easytier"：内置 easytier-core 组网 + 本机 WebSocket 监听 listenPort。
     *   extraJson: {"nativeLibDir":"...","etNetworkName":"...","etSecret":"...","etPeer":"...","etBind":""}
     * 密码用于校验 client 的 auth 帧（端到端）。
     */
    boolean start(
        String password,
        String relayHost,
        String deviceId,
        String sid,
        long epoch,
        String mode,
        int listenPort,
        String extraJson
    ) = 1;

    /** "idle" / "connecting" / "online" / "retrying | ..." / "online | et=<ip>:<port>" */
    String status() = 2;

    String getLogs() = 3;

    void clearLogs() = 4;

    void destroy() = 16777114;
}
