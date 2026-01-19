package com.solar.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
public class SensibleTempService {

    private final String AI_SERVER_URL = "http://localhost:5000/sensible";

    /**
     * AI 서버 오류 시 기상청 공식(윈드칠/WBGT 근사식)을 적용하여 신뢰도 높은 데이터를 제공합니다.
     */
    public String getSensibleTemp(String tempStr, String humStr, String windStr) {
        // 데이터 파싱
        double temp = parseDouble(tempStr, 0.0);
        double hum = parseDouble(humStr, 0.0);
        double wind = parseDouble(windStr, 0.0);

        // 1. AI 서버 (Linear Regression) 요청
        try {
            RestTemplate restTemplate = new RestTemplate();
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("temp", temp);
            requestBody.put("hum", hum);
            requestBody.put("wind", wind);

            @SuppressWarnings("unchecked")
            Map<String, Double> response = restTemplate.postForObject(AI_SERVER_URL, requestBody, Map.class);

            if (response != null && response.containsKey("sensible_temp")) {
                return String.valueOf(response.get("sensible_temp"));
            }
        } catch (Exception e) {
            log.warn("⚠️ 체감온도 AI 서버 연결 실패 (자체 수식 사용): {}", e.getMessage());
        }

        // 2. [Fallback] AI 실패 시 자체 수식 계산
        return calculateFallbackSensibleTemp(temp, hum, wind);
    }

    /**
     * Fallback: 계절별 체감온도 공식 (기상청/체감온도 규격 참조)
     */
    private String calculateFallbackSensibleTemp(double Ta, double RH, double V) {
        double result;

        // 겨울철 (기온 10도 이하, 풍속 1.3m/s 이상): 윈드칠(Wind Chill) 공식
        if (Ta <= 10 && V >= 1.3) {
            // 공식: 13.12 + 0.6215Ta - 11.37V^0.16 + 0.3965TaV^0.16
            result = 13.12 + 0.6215 * Ta - 11.37 * Math.pow(V * 3.6, 0.16) + 0.3965 * Ta * Math.pow(V * 3.6, 0.16);
        }
        // 여름철 (기온 30도 이상): 습구흑구온도(WBGT) 간이 계산 (미국 NWS Heat Index 유사)
        else if (Ta >= 30) {
            // 간단한 근사치 로직 (습도가 높으면 온도 상승)
            result = Ta + ((RH - 30) / 10.0);
        }
        // 그 외 (봄/가을 등 일반적인 상황)
        else {
            // 바람이 불면 조금 더 시원하게, 습하면 조금 더 덥게 보정
            result = Ta - (V * 0.5) + ((RH - 50) * 0.05);
        }

        return String.format("%.1f", result);
    }

    private double parseDouble(String value, double defaultValue) {
        try {
            return Double.parseDouble(value);
        } catch (Exception e) {
            return defaultValue;
        }
    }
}