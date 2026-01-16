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

    // 🔑 Open API 인증 키 (Service Key)
    private static final String SERVICE_KEY = "860d22d5afed47ba3bd53eb2e86fb3f152fa17a30ec99d05c043412e5e2d8d05";
    // 🌐 기상청 단기예보 조회 URL
    private static final String API_URL = "http://apis.data.go.kr/1360000/VilageFcstInfoService_2.0/getVilageFcst";

    /**
     * 1. 메인 메서드: 내일 날씨 조회
     * - Controller에서 호출하는 진입점입니다.
     * - 격자 좌표(nx, ny)를 받아 기상청 API를 호출하고, 결과를 파싱하여 반환합니다.
     */
    public Map<String, Object> getTomorrowWeather(int nx, int ny) {
        try {
            // API 호출을 위한 기준 시간 계산 (발표 시간 맞추기)
            String[] baseInfo = getBaseTime();

            // URI 생성 (파라미터 조합)
            URI uri = UriComponentsBuilder.fromUriString(API_URL)
                    .queryParam("serviceKey", SERVICE_KEY)
                    .queryParam("pageNo", "1")
                    .queryParam("numOfRows", "1000") // 넉넉하게 1000개 요청 (하루치 데이터 확보)
                    .queryParam("dataType", "JSON")
                    .queryParam("base_date", baseInfo[0])
                    .queryParam("base_time", baseInfo[1])
                    .queryParam("nx", nx)
                    .queryParam("ny", ny)
                    .encode().build().toUri();

            // API 호출 및 응답 수신
            RestTemplate restTemplate = new RestTemplate();
            String response = restTemplate.getForObject(uri, String.class);

            // JSON 파싱 및 데이터 정제 실행
            return parseWeather(response);

        } catch (Exception e) {
            e.printStackTrace();
            return null; // 에러 발생 시 null 반환 (Controller에서 처리)
        }
    }

    /**
     * 2. BaseTime 계산
     * - 기상청 API는 정해진 시간(02, 05, 08, 11, 14, 17, 20, 23시)에만 예보를 발표합니다.
     * - 현재 시간과 가장 가까운 '이전 발표 시간'을 찾아냅니다.
     */
    private String[] getBaseTime() {
        LocalDateTime now = LocalDateTime.now();

        // 발표 후 20분 정도 뒤에 API가 갱신되므로, 20분 전이면 1시간 전 데이터를 요청
        if (now.getMinute() < 20) now = now.minusHours(1);

        int hour = now.getHour();
        int[] releaseHours = {2, 5, 8, 11, 14, 17, 20, 23};
        int baseHour = 23;
        String baseDate = now.format(DateTimeFormatter.ofPattern("yyyyMMdd"));

        boolean isToday = false;
        // 현재 시간보다 바로 전의 발표 시간을 찾음
        for (int h : releaseHours) {
            if (hour >= h) { baseHour = h; isToday = true; }
        }

        // 자정~새벽 2시 사이라면, 어제 23시 데이터를 요청해야 함
        if (!isToday && hour < 2) {
            baseDate = now.minusDays(1).format(DateTimeFormatter.ofPattern("yyyyMMdd"));
            baseHour = 23;
        }
        return new String[]{baseDate, String.format("%02d00", baseHour)};
    }

    /**
     * 3. JSON 파싱 및 데이터 추출 (핵심 로직)
     * - 응답받은 JSON에서 '내일' 데이터를 필터링합니다.
     * - 최저/최고 기온을 찾고, 태양광 효율에 중요한 '낮 12시' 데이터를 추출합니다.
     */
    private Map<String, Object> parseWeather(String jsonResponse) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(jsonResponse);
        JsonNode items = root.path("response").path("body").path("items").path("item");

        // 타겟: 내일 날짜
        LocalDate tomorrowDate = LocalDate.now().plusDays(1);
        String tomorrow = tomorrowDate.format(DateTimeFormatter.ofPattern("yyyyMMdd"));

        Map<String, Object> result = new HashMap<>();
        Double minTemp = null;
        Double maxTemp = null;
        boolean foundNoon = false; // 12시 데이터 존재 여부 플래그

        for (JsonNode item : items) {
            String fcstDate = item.path("fcstDate").asText();
            String fcstTime = item.path("fcstTime").asText();
            String category = item.path("category").asText();
            String valStr = item.path("fcstValue").asText();

            // 내일 데이터만 처리
            if (fcstDate.equals(tomorrow)) {
                double val = 0.0;
                try {
                    // 강수량(PCP), 적설량(SNO)은 문자열(mm, cm) 파싱 필요
                    if (!category.equals("PCP") && !category.equals("SNO")) {
                        val = Double.parseDouble(valStr);
                    }
                } catch (NumberFormatException e) { val = 0.0; }

                // [데이터 1] 최저(TMN) / 최고(TMX) 기온 추출 -> UI 표시용
                if (category.equals("TMN")) minTemp = val;
                if (category.equals("TMX")) maxTemp = val;

                // [데이터 2] 낮 12시(1200) 데이터 추출 -> 태양광 효율 계산용
                // 하루 중 태양광 발전량이 가장 많은 시간대이므로 대표값으로 사용
                if (fcstTime.equals("1200")) {
                    if (category.equals("SKY")) {
                        // 구름 점수 변환: 1(맑음)->0, 3(구름많음)->5, 4(흐림)->10
                        double cloud = 0;
                        if (val == 1) cloud = 0;
                        else if (val == 3) cloud = 5;
                        else if (val >= 4) cloud = 10;
                        result.put("cloud", cloud);
                    }
                    else if (category.equals("PCP")) result.put("rain", parsePrecipitation(valStr));
                    else if (category.equals("SNO")) result.put("snow", parsePrecipitation(valStr));
                    else if (category.equals("REH")) result.put("humidity", val);
                    else if (category.equals("WSD")) result.put("wind", val);
                        // 강수확률(POP) 저장 -> UI 표시용 (예: 60%)
                    else if (category.equals("POP")) result.put("pop", val);

                    foundNoon = true;
                }
            }
        }

        // 데이터 보정 (Null 방지)
        if (minTemp == null) minTemp = 0.0;
        if (maxTemp == null) maxTemp = 20.0;

        // 최종 데이터 세팅
        result.put("temp", (minTemp + maxTemp) / 2.0); // AI 계산용 평균 기온
        result.put("minTemp", minTemp); // UI 표시용 최저
        result.put("maxTemp", maxTemp); // UI 표시용 최고

        // ⚡ [핵심] 천문학적 일사량 계산 (파이썬 봇과 동일 로직 적용)
        if (foundNoon) {
            double cloud = (double) result.getOrDefault("cloud", 0.0);

            // 위도 37.5(서울/경기 평균), 내일 날짜(DayOfYear), 12시 기준
            // 계절에 따른 태양 고도를 반영하여 이론적 일사량을 구하고, 구름양만큼 차감합니다.
            double radiation = calculateAstronomicalRadiation(37.5, tomorrowDate.getDayOfYear(), 12, cloud);

            result.put("radiation", Math.round(radiation * 100) / 100.0);
            result.put("sunshine", cloud <= 5 ? 1.0 : 0.0); // 구름 5 이하일 때 일조시간 1시간 인정
        }

        return result;
    }

    /**
     * ⚡ [신규] 천문학적 일사량 계산 메서드
     * - 계절(날짜)과 시간, 위도에 따른 태양의 정확한 높이(고도각)를 계산합니다.
     * - 파이썬 봇(predict.py)의 로직과 100% 동일하게 맞추어 데이터 일관성을 유지합니다.
     */
    private double calculateAstronomicalRadiation(double lat, int dayOfYear, int hour, double cloudScore) {
        // 1. 태양 적위 (Declination): 계절에 따른 태양의 남중 고도 변화
        double declination = 23.45 * Math.sin(Math.toRadians(360.0 * (284 + dayOfYear) / 365.0));

        // 2. 시간각 (Hour Angle): 정오(12시)를 기준으로 한 태양의 각도 (1시간 = 15도)
        double hourAngle = (hour - 12) * 15.0;

        // 3. 고도각 (Elevation): 지평선으로부터 태양이 얼마나 높이 떠 있는지 계산
        double latRad = Math.toRadians(lat);
        double decRad = Math.toRadians(declination);
        double haRad = Math.toRadians(hourAngle);



        double sinElevation = (Math.sin(latRad) * Math.sin(decRad)) +
                (Math.cos(latRad) * Math.cos(decRad) * Math.cos(haRad));
        double elevation = Math.toDegrees(Math.asin(Math.max(0, sinElevation)));

        // 해가 져서 고도각이 0 이하이면 발전량 0
        if (elevation <= 0) return 0.0;

        // 4. 이론적 최대 일사량 및 구름에 따른 감쇄 적용
        // (구름 점수 0~10점에 따라 효율 100% ~ 30%로 감소)
        double maxRadiation = 3.6 * Math.sin(Math.toRadians(elevation));
        double cloudFactor = 1.0 - (cloudScore / 10.0 * 0.7);

        return maxRadiation * cloudFactor;
    }

    /**
     * 강수량/적설량 문자열 파싱 헬퍼
     * - "10mm", "5cm", "강수없음" 등의 문자열을 숫자로 변환합니다.
     */
    private double parsePrecipitation(String valStr) {
        if (valStr.contains("mm") || valStr.contains("cm")) {
            try {
                return Double.parseDouble(valStr.replaceAll("[^0-9.]", ""));
            } catch(Exception e){ return 0.0; }
        }
        if (valStr.equals("강수없음") || valStr.equals("적설없음")) return 0.0;
        try {
            return Double.parseDouble(valStr);
        } catch (Exception e) { return 0.0; }
    }
}