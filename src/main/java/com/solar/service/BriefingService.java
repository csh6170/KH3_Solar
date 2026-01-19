package com.solar.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

@Slf4j
@Service
public class BriefingService {

    private final String AI_SERVER_URL = "http://localhost:5000/briefing";

    /**
     * AI 서버 오류 시 템플릿 문장을 조합하여 "응답 없음" 대신 기본적인 날씨 브리핑을 제공합니다.
     */
    public String getBriefing(String temp, String sky, String pty, String pop) {
        // 1. AI 서버 요청
        try {
            RestTemplate restTemplate = new RestTemplate();
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("temp", temp);
            requestBody.put("sky", sky);
            requestBody.put("pty", pty);
            requestBody.put("pop", pop);

            @SuppressWarnings("unchecked")
            Map<String, String> response = restTemplate.postForObject(AI_SERVER_URL, requestBody, Map.class);

            if (response != null && response.containsKey("script")) {
                return response.get("script");
            }
        } catch (Exception e) {
            log.warn("⚠️ AI 캐스터 서버 연결 실패 (템플릿 브리핑 사용): {}", e.getMessage());
        }

        // 2. [Fallback] 템플릿 기반 브리핑 생성
        return generateFallbackBriefing(temp, sky, pty, pop);
    }

    private String generateFallbackBriefing(String temp, String sky, String pty, String pop) {
        StringBuilder sb = new StringBuilder();

        // 인사
        sb.append("🎤 안녕하세요! 기상정보입니다.<br>");

        // 날씨 상태 묘사
        if (!"강수없음".equals(pty) && !"0".equals(pty)) {
            sb.append("현재 ☔ <b>").append(pty).append("</b>가 내리고 있습니다. 우산을 챙기세요!<br>");
        } else {
            sb.append("현재 하늘은 <b>").append(sky).append("</b> 상태이며, ");
        }

        // 기온 및 강수확률
        sb.append("기온은 <b>").append(temp).append("도</b>, 강수확률은 ").append(pop).append("%입니다.<br>");

        // 마무리 멘트 (기온별)
        double t = 0;
        try { t = Double.parseDouble(temp); } catch (Exception e) {}

        if (t > 28) sb.append("폭염에 주의하시고 수분을 충분히 섭취하세요. 🧊");
        else if (t < 5) sb.append("날씨가 많이 춥습니다. 따뜻하게 입으세요! 🧣");
        else sb.append("오늘도 즐거운 하루 보내세요! 😊");

        return sb.toString();
    }
}