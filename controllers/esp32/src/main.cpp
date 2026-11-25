#include <Arduino.h>
#include <climits>
#include <ESPmDNS.h>
#include <WebServer.h>
#include <WiFi.h>

#if __has_include("bitvoid_config.h")
#include "bitvoid_config.h"
#endif

#ifndef BITVOID_WIFI_SSID
#define BITVOID_WIFI_SSID ""
#endif
#ifndef BITVOID_WIFI_PASSWORD
#define BITVOID_WIFI_PASSWORD ""
#endif
#ifndef BITVOID_HTTP_PORT
#define BITVOID_HTTP_PORT 8080
#endif
#ifndef BITVOID_SCAN_INTERVAL_MS
#define BITVOID_SCAN_INTERVAL_MS 30000UL
#endif

static const char *SERVICE_NAME = "bitvoid";
static const char *IDENTITY_MODE = "local-identity-v1";
static const int PROTOCOL_VERSION = 2;
static const size_t MAX_REGISTRY_NODES = 24;
static const unsigned long REGISTRY_TTL_MS = 90000UL;

WebServer server(BITVOID_HTTP_PORT);
String nodeId;
unsigned long lastWifiScan = 0;
unsigned long lastReconnectAttempt = 0;

struct MeshNode {
  String nodeId;
  String nodeType;
  String directPortalUrl;
  bool portalAvailable;
  bool coordinator;
  int gatewayPriority;
  unsigned long lastSeen;
};

MeshNode registry[MAX_REGISTRY_NODES];
String wifiScanJson = "[]";

String escapeJson(const String &input) {
  String output;
  output.reserve(input.length() + 8);
  for (size_t i = 0; i < input.length(); i++) {
    const char c = input.charAt(i);
    if (c == '\\' || c == '"') {
      output += '\\';
      output += c;
    } else if (c == '\n') {
      output += "\\n";
    } else if (static_cast<uint8_t>(c) >= 0x20) {
      output += c;
    }
  }
  return output;
}

String jsonStringValue(const String &body, const String &key) {
  const String marker = "\"" + key + "\"";
  int cursor = body.indexOf(marker);
  if (cursor < 0) return "";
  cursor = body.indexOf(':', cursor + marker.length());
  if (cursor < 0) return "";
  cursor = body.indexOf('"', cursor + 1);
  if (cursor < 0) return "";
  const int end = body.indexOf('"', cursor + 1);
  if (end < 0) return "";
  return body.substring(cursor + 1, end);
}

bool jsonBoolValue(const String &body, const String &key, bool fallback) {
  const String marker = "\"" + key + "\"";
  int cursor = body.indexOf(marker);
  if (cursor < 0) return fallback;
  cursor = body.indexOf(':', cursor + marker.length());
  if (cursor < 0) return fallback;
  const String tail = body.substring(cursor + 1);
  if (tail.startsWith("true")) return true;
  if (tail.startsWith("false")) return false;
  return fallback;
}

String directUrl() {
  if (WiFi.status() != WL_CONNECTED) return "";
  return "http://" + WiFi.localIP().toString() + ":" + String(BITVOID_HTTP_PORT);
}

String identityJson() {
  return "{\"service\":\"bitvoid\",\"nodeId\":\"" + escapeJson(nodeId) +
         "\",\"nodeType\":\"esp32-coordinator\",\"protocolVersion\":2," 
         "\"identityMode\":\"local-identity-v1\",\"coordinator\":true," 
         "\"portalAvailable\":false,\"gatewayPriority\":10," 
         "\"hostname\":\"bitvoid-gateway.local\",\"directPortalUrl\":\"" +
         escapeJson(directUrl()) + "\",\"port\":" + String(BITVOID_HTTP_PORT) +
         ",\"features\":[\"mesh-v2\",\"registry\",\"background-wifi-scan\"]}";
}

void sendHeaders() {
  server.sendHeader("Cache-Control", "no-store");
  server.sendHeader("X-BitVoid-Node", "1");
  server.sendHeader("X-BitVoid-Protocol", String(PROTOCOL_VERSION));
  server.sendHeader("X-BitVoid-Identity", IDENTITY_MODE);
}

void sendJson(int status, const String &payload) {
  sendHeaders();
  server.send(status, "application/json", payload);
}

void registerNode(const MeshNode &incoming) {
  if (incoming.nodeId.length() == 0 || incoming.nodeId.length() > 128) return;
  int freeSlot = -1;
  int oldestSlot = 0;
  unsigned long oldestSeen = ULONG_MAX;
  for (size_t i = 0; i < MAX_REGISTRY_NODES; i++) {
    if (registry[i].nodeId == incoming.nodeId) {
      registry[i] = incoming;
      return;
    }
    if (registry[i].nodeId.length() == 0 && freeSlot < 0) freeSlot = static_cast<int>(i);
    if (registry[i].lastSeen < oldestSeen) {
      oldestSeen = registry[i].lastSeen;
      oldestSlot = static_cast<int>(i);
    }
  }
  registry[freeSlot >= 0 ? freeSlot : oldestSlot] = incoming;
}

String registryJson() {
  String payload = "{\"service\":\"bitvoid\",\"protocolVersion\":2," 
                   "\"identityMode\":\"local-identity-v1\",\"nodes\":[";
  payload += identityJson();
  const unsigned long now = millis();
  for (size_t i = 0; i < MAX_REGISTRY_NODES; i++) {
    if (registry[i].nodeId.length() == 0 || now - registry[i].lastSeen > REGISTRY_TTL_MS) continue;
    payload += ",";
    payload += "{\"service\":\"bitvoid\",\"nodeId\":\"" + escapeJson(registry[i].nodeId) +
               "\",\"nodeType\":\"" + escapeJson(registry[i].nodeType) +
               "\",\"protocolVersion\":2,\"identityMode\":\"local-identity-v1\"," 
               "\"coordinator\":" + String(registry[i].coordinator ? "true" : "false") +
               ",\"portalAvailable\":" + String(registry[i].portalAvailable ? "true" : "false") +
               ",\"gatewayPriority\":" + String(registry[i].gatewayPriority);
    if (registry[i].directPortalUrl.length() > 0) {
      payload += ",\"directPortalUrl\":\"" + escapeJson(registry[i].directPortalUrl) + "\"";
    }
    payload += "}";
  }
  payload += "]}";
  return payload;
}

void scanBackgroundWifi() {
  const int count = WiFi.scanNetworks(false, true);
  String payload = "[";
  for (int i = 0; i < count && i < 40; i++) {
    if (i > 0) payload += ",";
    payload += "{\"ssid\":\"" + escapeJson(WiFi.SSID(i)) + "\",\"rssi\":" +
               String(WiFi.RSSI(i)) + ",\"secure\":" +
               String(WiFi.encryptionType(i) == WIFI_AUTH_OPEN ? "false" : "true") + "}";
  }
  payload += "]";
  wifiScanJson = payload;
  WiFi.scanDelete();
}

void configureRoutes() {
  server.on("/api/node", HTTP_GET, []() { sendJson(200, identityJson()); });
  server.on("/api/mesh/nodes", HTTP_GET, []() { sendJson(200, registryJson()); });
  server.on("/api/wifi/scan", HTTP_GET, []() {
    sendJson(200, "{\"service\":\"bitvoid\",\"networks\":" + wifiScanJson + "}");
  });
  server.on("/api/mesh/register", HTTP_POST, []() {
    if (server.header("X-BitVoid-Identity") != IDENTITY_MODE) {
      sendJson(403, "{\"error\":\"identity_required\"}");
      return;
    }
    const String body = server.arg("plain");
    if (jsonStringValue(body, "service") != SERVICE_NAME ||
        jsonStringValue(body, "identityMode") != IDENTITY_MODE) {
      sendJson(400, "{\"error\":\"invalid_identity\"}");
      return;
    }
    MeshNode node;
    node.nodeId = jsonStringValue(body, "nodeId");
    node.nodeType = jsonStringValue(body, "nodeType");
    node.directPortalUrl = jsonStringValue(body, "directPortalUrl");
    node.portalAvailable = jsonBoolValue(body, "portalAvailable", false);
    node.coordinator = jsonBoolValue(body, "coordinator", false);
    node.gatewayPriority = 100;
    node.lastSeen = millis();
    registerNode(node);
    sendJson(200, "{\"accepted\":true,\"nodeId\":\"" + escapeJson(node.nodeId) + "\"}");
  });
  server.on("/health", HTTP_GET, []() {
    sendJson(200, "{\"status\":\"ok\",\"nodeId\":\"" + escapeJson(nodeId) + "\"}");
  });
  server.on("/", HTTP_GET, []() {
    sendJson(200, "{\"service\":\"bitvoid\",\"status\":\"ready\",\"api\":\"/api\"}");
  });
  server.onNotFound([]() { sendJson(404, "{\"error\":\"not_found\"}"); });
  const char *headers[] = {"X-BitVoid-Identity"};
  server.collectHeaders(headers, 1);
}

void connectWifi() {
  if (strlen(BITVOID_WIFI_SSID) == 0) return;
  WiFi.mode(WIFI_STA);
  WiFi.setAutoReconnect(true);
  WiFi.persistent(false);
  WiFi.begin(BITVOID_WIFI_SSID, BITVOID_WIFI_PASSWORD);
}

void setup() {
  Serial.begin(115200);
  const uint64_t chip = ESP.getEfuseMac();
  nodeId = "esp32-" + String(static_cast<uint32_t>(chip >> 32), HEX) +
           String(static_cast<uint32_t>(chip), HEX);
  connectWifi();
  configureRoutes();
  server.begin();
  Serial.printf("BitVoid coordinator %s listening on port %d\n", nodeId.c_str(), BITVOID_HTTP_PORT);
}

void loop() {
  server.handleClient();
  const unsigned long now = millis();
  if (WiFi.status() != WL_CONNECTED && now - lastReconnectAttempt > 10000UL) {
    lastReconnectAttempt = now;
    connectWifi();
  }
  if (WiFi.status() == WL_CONNECTED) {
    static bool mdnsStarted = false;
    if (!mdnsStarted) {
      mdnsStarted = MDNS.begin("bitvoid-gateway");
      if (mdnsStarted) {
        MDNS.addService("bitvoid", "tcp", BITVOID_HTTP_PORT);
        MDNS.addService("http", "tcp", BITVOID_HTTP_PORT);
      }
    }
    if (lastWifiScan == 0 || now - lastWifiScan > BITVOID_SCAN_INTERVAL_MS) {
      lastWifiScan = now;
      scanBackgroundWifi();
    }
  }
  delay(2);
}
