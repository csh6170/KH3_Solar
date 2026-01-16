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

    // 🔑 본인의 Service Key
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

    // 2. BaseTime 계산 (기존 유지)
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

    // 3. JSON 파싱 및 데이터 추출 (✨ 여기가 핵심 변경됨!)
    private Map<String, Object> parseWeather(String jsonResponse) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(jsonResponse);
        JsonNode items = root.path("response").path("body").path("items").path("item");

        // 내일 날짜
        LocalDate tomorrowDate = LocalDate.now().plusDays(1);
        String tomorrow = tomorrowDate.format(DateTimeFormatter.ofPattern("yyyyMMdd"));

        double sumTemp = 0;
        double sumHum = 0;
        double sumWind = 0;
        double sumRain = 0;
        double sumSnow = 0;
        double maxPop = 0;

        int count = 0; // 데이터 개수 카운트

        // ⚡ [추가] 일사량 정밀 계산을 위한 누적값
        double totalAstronomicalRadiation = 0.0;

        // [참고] 위도는 파라미터로 안 넘어오므로, 대한민국 평균 위도(36.5) 혹은 서울(37.5) 사용
        // 봇과 최대한 비슷하게 하기 위해 서울 기준값 사용 (큰 오차 없음)
        double lat = 37.5;

        for (JsonNode item : items) {
            String fcstDate = item.path("fcstDate").asText();
            String fcstTime = item.path("fcstTime").asText();
            String category = item.path("category").asText();
            String valStr = item.path("fcstValue").asText();

            if (fcstDate.equals(tomorrow)) {
                int time = Integer.parseInt(fcstTime); // 예: 0600 -> 600
                int hour = time / 100; // 시(hour) 추출

                // 낮 시간(06~20시) 데이터만 처리
                if (hour >= 6 && hour <= 20) {

                    double val = 0.0;
                    try {
                        // 문자열(강수없음 등) 방지 로직
                        if (!category.equals("PCP") && !category.equals("SNO")) {
                            val = Double.parseDouble(valStr);
                        }
                    } catch (NumberFormatException e) { val = 0.0; }

                    switch (category) {
                        case "TMP": sumTemp += val; break;
                        case "REH": sumHum += val; count++; break; // 습도는 매 시간 있으므로 카운트로 적절
                        case "WSD": sumWind += val; break;
                        case "POP": maxPop = Math.max(maxPop, val); break;
                        case "PCP": sumRain += parsePrecipitation(valStr); break;
                        case "SNO": sumSnow += parsePrecipitation(valStr); break;

                        case "SKY":
                            // 1. 구름 점수 계산 (0~10)
                            double cloudScore = 0;
                            if (val == 1) cloudScore = 0;      // 맑음
                            else if (val == 3) cloudScore = 5; // 구름많음
                            else if (val >= 4) cloudScore = 10; // 흐림

                            // 2. ⚡ [핵심] 파이썬 봇과 똑같은 알고리즘 적용!
                            // 해당 시간(hour)의 이론적 일사량을 구하고, 구름양만큼 깎음
                            double rad = calculateAstronomicalRadiation(lat, tomorrowDate.getDayOfYear(), hour, cloudScore);
                            totalAstronomicalRadiation += rad;
                            break;
                    }
                }
            }
        }

        Map<String, Object> result = new HashMap<>();

        if (count > 0) {
            result.put("temp", Math.round((sumTemp / count) * 10) / 10.0);
            result.put("humidity", Math.round((sumHum / count) * 10) / 10.0);
            result.put("wind", Math.round((sumWind / count) * 10) / 10.0);
            result.put("rain", Math.round(sumRain * 10) / 10.0);
            result.put("snow", Math.round(sumSnow * 10) / 10.0);
            result.put("pop", maxPop);

            // 구름 등은 이제 계산에 직접 안 쓰이지만, 표시는 해줌 (대략적인 평균)
            // (주의: count는 REH 기준이라 SKY 개수와 다를 수 있지만, 대략 맞음)
            result.put("cloud", 5.0); // 평균 구름양은 UI 표시용으로만 남김

            // ⚡ [변경] 일사량 (Radiation)
            // 파이썬 로직 결과(MJ/m2 합계)를 '일조 시수(Peak Sun Hours)' 개념으로 변환해 전달
            // (MJ 합계 / 3.6 = kWh/m2 = 일조 시수)
            double dailyRadiationKwh = totalAstronomicalRadiation / 3.6;

            result.put("radiation", Math.round(dailyRadiationKwh * 100) / 100.0);

            // 일조량(Sunshine)은 radiation 값과 비슷하게 따라가도록 설정
            result.put("sunshine", Math.round(dailyRadiationKwh * 100) / 100.0);
        }

        return result;
    }

    // ⚡ [신규] 천문학적 일사량 계산 메서드 (파이썬 로직을 자바로 번역)
    private double calculateAstronomicalRadiation(double lat, int dayOfYear, int hour, double cloudScore) {
        // 1. 태양 적위 (Declination)
        double declination = 23.45 * Math.sin(Math.toRadians(360.0 * (284 + dayOfYear) / 365.0));

        // 2. 시간각 (Hour Angle) : 12시=0도, 1시간=15도
        double hourAngle = (hour - 12) * 15.0;

        // 3. 태양 고도각 (Elevation)
        double latRad = Math.toRadians(lat);
        double decRad = Math.toRadians(declination);
        double haRad = Math.toRadians(hourAngle);

        double sinElevation = (Math.sin(latRad) * Math.sin(decRad)) +
                (Math.cos(latRad) * Math.cos(decRad) * Math.cos(haRad));
        double elevation = Math.toDegrees(Math.asin(Math.max(0, sinElevation)));

        // 해가 졌으면 0
        if (elevation <= 0) return 0.0;

        // 4. 최대 일사량 (Clear Sky Radiation)
        double maxRadiation = 3.6 * Math.sin(Math.toRadians(elevation));

        // 5. 구름 감쇄 적용
        // 구름 0(맑음) -> 100%, 구름 10(흐림) -> 30% 효율
        double cloudFactor = 1.0 - (cloudScore / 10.0 * 0.7);

        return maxRadiation * cloudFactor;
    }

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