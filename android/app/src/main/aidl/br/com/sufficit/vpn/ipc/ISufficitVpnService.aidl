package br.com.sufficit.vpn.ipc;

/** Contrato estável usado pelos agentes Sufficit para compartilhar um único VpnService. */
interface ISufficitVpnService {
    int getProtocolVersion();
    String getStatusJson();
    void connect();
    void disconnect();
    void enroll(String enrollmentEnvelope);
    boolean registerLocalService(String name, int port, String protocol);
    boolean unregisterLocalService(String name, int port, String protocol);
}
