package com.solar.service;

import com.solar.dto.WeatherDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

@Slf4j
@Service
public class DjService {

    private final String AI_SERVER_URL = "http://localhost:5000/dj";

    /**
     * AI 서버 오류 시 날씨(비/맑음/흐림)에 맞는 고정된 유튜브 플레이리스트를 추천하여 빈 화면을 방지합니다.
     */
    public void setMusicRecommendation(WeatherDTO dto) {
        int currentHour = LocalDateTime.now(ZoneId.of("Asia/Seoul")).getHour();
        String pty = (dto.getPTY() != null) ? dto.getPTY() : "0";
        String sky = (dto.getSKY() != null) ? dto.getSKY() : "맑음";

        // 1. AI 서버 요청
        try {
            RestTemplate restTemplate = new RestTemplate();
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("pty", pty);
            requestBody.put("sky", sky);
            requestBody.put("hour", currentHour);

            @SuppressWarnings("unchecked")
            Map<String, String> response = restTemplate.postForObject(AI_SERVER_URL, requestBody, Map.class);

            if (response != null) {
                dto.setYoutubeVideoId(response.get("videoId"));
                dto.setMusicComment(response.get("comment"));
                return;
            }
        } catch (Exception e) {
            log.warn("⚠️ AI DJ 서버 연결 실패 (로컬 리스트 사용): {}", e.getMessage());
        }

        // 2. [Fallback] 로컬 추천 로직
        setFallbackMusic(dto, pty, sky);
    }

    private void setFallbackMusic(WeatherDTO dto, String pty, String sky) {
        // 비/눈이 올 때
        if (!"강수없음".equals(pty) && !"0".equals(pty)) {
            dto.setYoutubeVideoId("PTXcP6EvMB0"); // Rain Lofi
            dto.setMusicComment("🌧️ 빗소리와 함께 차분한 음악을 준비했어요. (AI 연결 불안정)");
        }
        // 맑은 날
        else if ("맑음".equals(sky)) {
            dto.setYoutubeVideoId("DRdAgeHuL_g"); // Drive Music
            dto.setMusicComment("☀️ 맑은 날엔 신나는 음악이 딱이죠! (기본 추천)");
        }
        // 흐림/구름
        else {
            dto.setYoutubeVideoId("3kZd1kHf8bU"); // Indie Music
            dto.setMusicComment("☁️ 흐린 날씨에 어울리는 감성적인 곡입니다. (기본 추천)");
        }
    }
}