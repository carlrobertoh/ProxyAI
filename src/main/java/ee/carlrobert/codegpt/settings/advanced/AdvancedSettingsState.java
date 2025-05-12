package ee.carlrobert.codegpt.settings.advanced;

import java.net.Proxy;
import java.util.Objects;

public class AdvancedSettingsState {

  private String proxyHost = "";
  private int proxyPort;
  private Proxy.Type proxyType = Proxy.Type.SOCKS;
  private boolean proxyAuthSelected;
  private String proxyUsername;
  private String proxyPassword;
  private int connectTimeout = 120;
  private int readTimeout = 600;

  public String getProxyHost() {
    return proxyHost;
  }

  public void setProxyHost(String proxyHost) {
    this.proxyHost = proxyHost;
  }

  public int getProxyPort() {
    return proxyPort;
  }

  public void setProxyPort(int proxyPort) {
    this.proxyPort = proxyPort;
  }

  public Proxy.Type getProxyType() {
    return proxyType;
  }

  public void setProxyType(Proxy.Type proxyType) {
    this.proxyType = proxyType;
  }

  public boolean isProxyAuthSelected() {
    return proxyAuthSelected;
  }

  public void setProxyAuthSelected(boolean proxyAuthSelected) {
    this.proxyAuthSelected = proxyAuthSelected;
  }

  public String getProxyUsername() {
    return proxyUsername;
  }

  public void setProxyUsername(String proxyUsername) {
    this.proxyUsername = proxyUsername;
  }

  public String getProxyPassword() {
    return proxyPassword;
  }

  public void setProxyPassword(String proxyPassword) {
    this.proxyPassword = proxyPassword;
  }

  public int getConnectTimeout() {
    return connectTimeout;
  }

  public void setConnectTimeout(int connectTimeout) {
    this.connectTimeout = connectTimeout;
  }

  public int getReadTimeout() {
    return readTimeout;
  }

  public void setReadTimeout(int readTimeout) {
    this.readTimeout = readTimeout;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof AdvancedSettingsState that)) {
      return false;
    }
    return getProxyPort() == that.getProxyPort()
        && isProxyAuthSelected() == that.isProxyAuthSelected()
        && getConnectTimeout() == that.getConnectTimeout()
        && getReadTimeout() == that.getReadTimeout()
        && Objects.equals(getProxyHost(), that.getProxyHost())
        && getProxyType() == that.getProxyType()
        && Objects.equals(getProxyUsername(), that.getProxyUsername())
        && Objects.equals(getProxyPassword(), that.getProxyPassword());
  }

  @Override
  public int hashCode() {
    return Objects.hash(getProxyHost(), getProxyPort(), getProxyType(), isProxyAuthSelected(),
        getProxyUsername(), getProxyPassword(), getConnectTimeout(), getReadTimeout());
  }
}
