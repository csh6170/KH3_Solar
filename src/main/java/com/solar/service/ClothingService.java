package com.solar.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
public class ClothingService {

    // 파이썬 AI 서버 주소
    private final String AI_SERVER_URL = "http://localhost:5000/predict";

    /**
     * Python 서버에 예측을 요청하고, 실패하면 자체 로직(Fallback)을 사용합니다.
     */
    public String recommendOutfit(String temp, String pty, String wind) {
        double temperature;
        try {
            temperature = Double.parseDouble(temp);
        } catch (NumberFormatException e) {
            return "기온 정보 오류";
        }

        // 1. 파이썬 서버로 요청 시도
        try {
            RestTemplate restTemplate = new RestTemplate();

            // 보낼 데이터 (JSON)
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("temp", temperature);
            requestBody.put("pty", pty);

            // POST 요청 전송
            @SuppressWarnings("unchecked")
            Map<String, String> response = restTemplate.postForObject(AI_SERVER_URL, requestBody, Map.class);

            if (response != null && response.containsKey("recommendation")) {
                log.info("🐍 파이썬 AI 서버 응답 성공");
                return response.get("recommendation");
            }
        } catch (Exception e) {
            log.warn("⚠️ 파이썬 AI 서버 연결 실패 (자체 로직 사용): {}", e.getMessage());
        }

        // 2. [Fallback] 파이썬 서버 실패 시 기존 Java 로직 사용
        return getFallbackRecommendation(temperature, pty);
    }

    // 기존의 if-else 로직 (백업용)
    private String getFallbackRecommendation(double temperature, String pty) {
        StringBuilder sb = new StringBuilder();

        // 1. 강수 여부에 따른 악세사리 추천 (우선순위 높음)
        if (pty != null && (pty.equals("비") || pty.equals("비/눈") || pty.equals("소나기"))) {
            sb.append("☔ <b>비가 옵니다!</b> 장화나 레인부츠를 추천해요. ");
        } else if (pty != null && (pty.equals("눈") || pty.equals("진눈깨비"))) {
            sb.append("⛄ <b>눈이 옵니다!</b> 미끄러지지 않는 신발을 신으세요. ");
        }

        // 2. 기온별 옷차림 분류 (Decision Tree Logic)
        if (temperature >= 28) {
            sb.append("민소매, 반바지, 짧은 치마, 린넨 소재의 시원한 옷");
        } else if (temperature >= 23) {
            sb.append("반팔 티셔츠, 얇은 셔츠, 반바지, 면바지");
        } else if (temperature >= 20) {
            sb.append("얇은 가디건, 긴팔 티셔츠, 면바지, 청바지");
        } else if (temperature >= 17) {
            sb.append("얇은 니트, 맨투맨, 가디건, 청바지");
        } else if (temperature >= 12) {
            sb.append("자켓, 가디건, 청자켓, 니트, 스타킹, 청바지");
        } else if (temperature >= 9) {
            sb.append("트렌치코트, 야상, 점퍼, 니트, 스타킹");
        } else if (temperature >= 5) {
            sb.append("울 코트, 히트텍, 가죽 옷, 기모 소재");
        } else {
            // 4도 이하
            sb.append("패딩, 두꺼운 코트, 목도리, 장갑, 기모 바지 (완전 무장 필수!)");
        }

        return sb.toString();
    }

    // 추천 아이콘 반환 (UI용)
    public String getOutfitIcon(String temp) {
        try {
            double t = Double.parseDouble(temp);
            if (t >= 23) return "fas fa-tshirt text-orange-400"; // 반팔
            if (t >= 17) return "fas fa-user-tie text-green-500"; // 긴팔/셔츠
            if (t >= 9) return "fas fa-user-secret text-blue-500"; // 코트/자켓
            return "fas fa-snowman text-blue-300"; // 패딩/겨울옷
        } catch (Exception e) {
            return "fas fa-question";
        }
    }
}