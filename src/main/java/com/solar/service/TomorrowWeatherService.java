package com.solar.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

@Service
public class TomorrowWeatherService {

    // 🔑 본인의 Service Key (Encoding 된 키가 필요할 수도 있음, 에러 시 확인)
    private static final String SERVICE_KEY = "860d22d5afed47ba3bd53eb2e86fb3f152fa17a30ec99d05c043412e5e2d8d05";
    private static final String API_URL = "http://apis.data.go.kr/1360000/VilageFcstInfoService_2.0/getVilageFcst";

    // 1. 메인 메서드: 내일 날씨 조회
    public Map<String, Object> getTomorrowWeather(int nx, int ny) {
        try {
            String[] baseInfo = getBaseTime();
            String baseDate = baseInfo[0];
            String baseTime = baseInfo[1];

            URI uri = UriComponentsBuilder.fromUriString(API_URL)
                    .queryParam("serviceKey", SERVICE_KEY)
                    .queryParam("pageNo", "1")
                    .queryParam("numOfRows", "1000")
                    .queryParam("dataType", "JSON")
                    .queryParam("base_date", baseDate)
                    .queryParam("base_time", baseTime)
                    .queryParam("nx", nx)
                    .queryParam("ny", ny)
                    .encode()
                    .build()
                    .toUri();

            RestTemplate restTemplate = new RestTemplate();
            String response = restTemplate.getForObject(uri, String.class);

            return parseWeather(response);

        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    // 2. BaseTime 계산 (기존 로직 유지 - 안정적임)
    private String[] getBaseTime() {
        LocalDateTime now = LocalDateTime.now();
        if (now.getMinute() < 20) {
            now = now.minusHours(1);
        }
        int hour = now.getHour();
        int[] releaseHours = {2, 5, 8, 11, 14, 17, 20, 23};
        int baseHour = 23;
        String baseDate = now.format(DateTimeFormatter.ofPattern("yyyyMMdd"));

        boolean isToday = false;
        for (int h : releaseHours) {
            if (hour >= h) {
                baseHour = h;
                isToday = true;
            }
        }
        if (!isToday && hour < 2) {
            baseDate = now.minusDays(1).format(DateTimeFormatter.ofPattern("yyyyMMdd"));
            baseHour = 23;
        }
        String baseTime = String.format("%02d00", baseHour);
        return new String[]{baseDate, baseTime};
    }

    // 3. JSON 파싱 및 데이터 추출 (POP 추가 및 일조량 계산 포함)
    private Map<String, Object> parseWeather(String jsonResponse) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(jsonResponse);
        JsonNode items = root.path("response").path("body").path("items").path("item");

        String tomorrow = LocalDate.now().plusDays(1).format(DateTimeFormatter.ofPattern("yyyyMMdd"));

        double sumTemp = 0;
        double sumHum = 0;
        double sumWind = 0;
        double sumRain = 0;
        double sumSnow = 0;
        double maxPop = 0; // 강수확률은 합계가 아니라 최대값으로 (하루 중 가장 높은 확률)

        // 일조량 계산용 변수
        double totalSunshineScore = 0;
        double totalCloudScore = 0;
        int count = 0;

        for (JsonNode item : items) {
            String fcstDate = item.path("fcstDate").asText();
            String fcstTime = item.path("fcstTime").asText();
            String category = item.path("category").asText();
            String valStr = item.path("fcstValue").asText();

            if (fcstDate.equals(tomorrow)) {
                int time = Integer.parseInt(fcstTime);

                // 태양광 발전은 낮 시간(06~20시) 데이터가 중요하므로 필터링
                if (time >= 600 && time <= 2000) {

                    double val = 0.0;
                    try {
                        if (!category.equals("PCP") && !category.equals("SNO")) {
                            val = Double.parseDouble(valStr);
                        }
                    } catch (NumberFormatException e) { val = 0.0; }

                    switch (category) {
                        case "TMP": sumTemp += val; break;
                        case "REH": sumHum += val; count++; break; // 시간 카운트 기준
                        case "WSD": sumWind += val; break;
                        case "POP": maxPop = Math.max(maxPop, val); break; // ✅ 최대 강수확률 저장
                        case "PCP": sumRain += parsePrecipitation(valStr); break;
                        case "SNO": sumSnow += parsePrecipitation(valStr); break;

                        case "SKY":
                            // 구름 점수 및 일조량 점수 계산
                            if (val == 1) { // 맑음
                                totalCloudScore += 0;
                                totalSunshineScore += 1.0;
                            } else if (val == 3) { // 구름많음
                                totalCloudScore += 5;
                                totalSunshineScore += 0.5;
                            } else if (val >= 4) { // 흐림
                                totalCloudScore += 10;
                                totalSunshineScore += 0.0;
                            }
                            break;
                    }
                }
            }
        }

        Map<String, Object> result = new HashMap<>(); // Object 타입으로 변경

        if (count > 0) {
            // 평균값 계산
            result.put("temp", Math.round((sumTemp / count) * 10) / 10.0);
            result.put("humidity", Math.round((sumHum / count) * 10) / 10.0);
            result.put("wind", Math.round((sumWind / count) * 10) / 10.0);
            result.put("rain", Math.round(sumRain * 10) / 10.0);
            result.put("snow", Math.round(sumSnow * 10) / 10.0);

            // ✅ 강수확률 추가 (Double로 변환)
            result.put("pop", maxPop);

            // 일조량 & 구름 계산
            double avgCloud = totalCloudScore / count;
            double avgSunshine = totalSunshineScore / count;

            if (sumRain > 0 || sumSnow > 0) {
                avgSunshine *= 0.5; // 비/눈 오면 일조량 패널티
            }

            result.put("cloud", Math.round(avgCloud * 10) / 10.0);
            result.put("sunshine", Math.round(avgSunshine * 100) / 100.0);

            // 일사량 추정
            double estRadiation = avgSunshine * 3.5;
            if (estRadiation < 0.5) estRadiation = 0.5;
            result.put("radiation", Math.round(estRadiation * 10) / 10.0);
        }

        return result;
    }

    // 강수량 파싱 헬퍼 메서드
    private double parsePrecipitation(String valStr) {
        if (valStr.contains("mm") || valStr.contains("cm")) {
            return Double.parseDouble(valStr.replaceAll("[^0-9.]", ""));
        }
        if (valStr.equals("강수없음") || valStr.equals("적설없음")) return 0.0;
        try {
            return Double.parseDouble(valStr);
        } catch (Exception e) {
            return 0.0;
        }
    }
}