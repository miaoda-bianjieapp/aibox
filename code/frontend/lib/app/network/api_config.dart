abstract final class ApiConfig {
  // Android emulator default. For a real device, use the PC WLAN IPv4 on the
  // same Wi-Fi and keep that local change out of Git.
  static const baseUrl = 'http://10.0.2.2:8080/api/v1';
}
