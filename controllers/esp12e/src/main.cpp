#include <Arduino.h>
#include <ESP8266WiFi.h>
#include <ESP8266WebServer.h>
#include <ArduinoJson.h>
#include <LittleFS.h>
#include <ESP8266mDNS.h>
#include <memory>

#if __has_include("bitvoid_config.h")
#include "bitvoid_config.h"
#else
#include "bitvoid_config.example.h"
#endif

#ifndef BITVOID_AP_PASSWORD
#define BITVOID_AP_PASSWORD "CHANGE_ME_12345"
#endif

ESP8266WebServer server(80);
const IPAddress gatewayIp(192, 168, 4, 1);
const IPAddress subnetMask(255, 255, 255, 0);
const char* protocolIdentity = "local-identity-v1";
unsigned long bootMillis = 0;

void sendJson(int status, JsonDocument& doc) {
  String body;
  serializeJson(doc, body);
  server.sendHeader("Cache-Control", "no-store");
  server.send(status, "application/json; charset=utf-8", body);
}

void sendNodeIdentity() {
  server.sendHeader("X-BitVoid-Node", "1");
  server.sendHeader("X-BitVoid-Protocol", "2");
  server.sendHeader("X-BitVoid-Identity", protocolIdentity);
  StaticJsonDocument<384> doc;
  doc["service"] = "bitvoid";
  doc["nodeId"] = BITVOID_GATEWAY_ID;
  doc["nodeType"] = "esp8266-coordinator";
  doc["protocolVersion"] = 2;
  doc["coordinator"] = true;
  doc["portalAvailable"] = false;
  doc["gatewayPriority"] = 10;
  sendJson(200, doc);
}

void sendGatewayInfo() {
  StaticJsonDocument<768> doc;
  doc["service"] = "bitvoid";
  doc["gatewayId"] = BITVOID_GATEWAY_ID;
  doc["displayName"] = BITVOID_GATEWAY_NAME;
  JsonObject hardware = doc.createNestedObject("hardware");
  hardware["family"] = "esp8266";
  hardware["model"] = "esp-12e";
  JsonObject protocol = doc.createNestedObject("protocol");
  protocol["name"] = "bitvoid-gateway";
  protocol["major"] = 1;
  protocol["minor"] = 0;
  JsonObject identity = doc.createNestedObject("identity");
  identity["mode"] = protocolIdentity;
  identity["verified"] = true;
  JsonArray caps = doc.createNestedArray("capabilities");
  caps.add("wifi-ap");
  caps.add("discovery");
  caps.add("health");
  JsonObject limits = doc.createNestedObject("limits");
  limits["maxClients"] = BITVOID_MAX_CLIENTS;
  JsonObject links = doc.createNestedObject("links");
  links["health"] = "/api/v1/health";
  links["services"] = "/api/v1/services";
  sendJson(200, doc);
}

void sendHealth() {
  StaticJsonDocument<192> doc;
  doc["ok"] = true;
  doc["status"] = "ready";
  doc["uptimeSeconds"] = (millis() - bootMillis) / 1000;
  sendJson(200, doc);
}

void sendServices() {
  StaticJsonDocument<256> doc;
  JsonArray services = doc.createNestedArray("services");
  JsonObject discovery = services.createNestedObject();
  discovery["id"] = "discovery";
  discovery["available"] = true;
  JsonObject media = services.createNestedObject();
  media["id"] = "media";
  media["available"] = false;
  JsonObject messaging = services.createNestedObject();
  messaging["id"] = "messaging";
  messaging["available"] = false;
  sendJson(200, doc);
}

void sendDevices() {
  StaticJsonDocument<64> doc;
  doc.createNestedArray("devices");
  sendJson(200, doc);
}

void sendNotImplemented() {
  StaticJsonDocument<192> doc;
  doc["error"] = "not_implemented";
  doc["message"] = "This ESP-12E build does not provide this service.";
  sendJson(501, doc);
}

void setupRoutes() {
  server.on("/api/node", HTTP_GET, sendNodeIdentity);
  server.on("/api/v1/gateway/info", HTTP_GET, sendGatewayInfo);
  server.on("/api/v1/health", HTTP_GET, sendHealth);
  server.on("/health", HTTP_GET, sendHealth);
  server.on("/api/v1/services", HTTP_GET, sendServices);
  server.on("/api/v1/devices", HTTP_GET, sendDevices);
  server.on("/api/v1/pairing/request", HTTP_POST, sendNotImplemented);
  server.on("/api/v1/pairing/approve", HTTP_POST, sendNotImplemented);
  server.on("/api/mesh/nodes", HTTP_GET, []() {
    StaticJsonDocument<96> doc;
    doc["service"] = "bitvoid";
    doc["protocolVersion"] = 2;
    doc.createNestedArray("nodes");
    sendJson(200, doc);
  });
  server.on("/generate_204", HTTP_GET, []() {
    server.sendHeader("Location", "http://192.168.4.1/");
    server.send(302, "text/plain", "");
  });
  server.on("/", HTTP_GET, []() {
    server.send(200, "text/plain", "BitVoid ESP-12E gateway is online. API: /api/v1/gateway/info");
  });
  server.onNotFound([]() {
    StaticJsonDocument<128> doc;
    doc["error"] = "not_found";
    sendJson(404, doc);
  });
}

void setup() {
  Serial.begin(115200);
  delay(100);
  bootMillis = millis();

  if (String(BITVOID_AP_PASSWORD).length() < 8 || String(BITVOID_AP_PASSWORD).length() > 63 ||
      String(BITVOID_AP_PASSWORD) == "CHANGE_ME_12345") {
    Serial.println("ERROR: Set a unique WPA2 password in src/bitvoid_config.h before deployment.");
    while (true) delay(1000);
  }

  WiFi.persistent(false);
  WiFi.mode(WIFI_AP);
  WiFi.softAPConfig(gatewayIp, gatewayIp, subnetMask);
  if (!WiFi.softAP(BITVOID_AP_SSID, BITVOID_AP_PASSWORD, 1, false, BITVOID_MAX_CLIENTS)) {
    Serial.println("ERROR: Could not start BitVoid access point.");
    while (true) delay(1000);
  }

  MDNS.begin("bitvoid");
  MDNS.addService("http", "tcp", 80);
  setupRoutes();
  server.begin();

  Serial.println();
  Serial.println("BitVoid Universal Gateway API v1");
  Serial.print("SSID: "); Serial.println(BITVOID_AP_SSID);
  Serial.print("Gateway: "); Serial.println(WiFi.softAPIP());
  Serial.println("API: http://192.168.4.1/api/v1/gateway/info");
}

void loop() {
  server.handleClient();
  MDNS.update();
  yield();
}
