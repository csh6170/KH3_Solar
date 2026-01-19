package com.solar.service;

import com.solar.dto.WeatherDTO;
import com.solar.dto.EarthquakeDTO;
import com.solar.dto.TyphoonDTO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.solar.service.ClothingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@Service
@RequiredArgsConstructor
public class WeatherService {

    private final ClothingService clothingService;
    private final BriefingService briefingService;
    private final DjService djService;
    private final SensibleTempService sensibleTempService;

    // [최적화] ObjectMapper를 매번 생성하지 않고 재사용
    private final ObjectMapper mapper = new ObjectMapper();

    // 병렬 처리를 위한 스레드 풀 (API 호출이 많으므로 넉넉하게 설정)
    private final ExecutorService executor = Executors.newFixedThreadPool(10);

    private final String API_KEY = "eaab499069c4dc1e503f0de460f8fd9add7a1dc08fd28a6b6a2074bd0d2e3162"; // 공공데이터포털에서 발급받은 서비스키

    // API URL 목록
    private final String URL_VILAGE = "http://apis.data.go.kr/1360000/VilageFcstInfoService_2.0/getVilageFcst";     // 동네예보조회
    private final String URL_ULTRA  = "http://apis.data.go.kr/1360000/VilageFcstInfoService_2.0/getUltraSrtFcst";   // 초단기예보조회
    private final String URL_UV     = "http://apis.data.go.kr/1360000/LivingWthrIdxServiceV4/getUVIdxV4";           // 자외선지수조회
    private final String URL_WARN   = "http://apis.data.go.kr/1360000/WthrWrnInfoService/getWthrWrnList";           // 기상특보조회
    private final String URL_EQK    = "http://apis.data.go.kr/1360000/EqkInfoService/getEqkMsgList";                // 지진정보조회
    private final String URL_TYPHOON= "http://apis.data.go.kr/1360000/TyphoonInfoService/getTyphoonInfoList";       // 태풍정보조회
    private final String URL_DUST   = "http://apis.data.go.kr/B552584/ArpltnInforInqireSvc/getCtprvnRltmMesureDnsty"; // 미세먼지정보조회

    // 보건기상지수 (꽃가루) URL
    private final String URL_POLLEN_OAK   = "http://apis.data.go.kr/1360000/HealthWthrIdxServiceV4/getOakPollenRiskIdxV4";
    private final String URL_POLLEN_PINE  = "http://apis.data.go.kr/1360000/HealthWthrIdxServiceV4/getPinePollenRiskIdxV4";
    private final String URL_POLLEN_WEEDS = "http://apis.data.go.kr/1360000/HealthWthrIdxServiceV4/getWeedsPollenRiskIdxV4";

    private final String URL_SUNRISE = "https://api.sunrise-sunset.org/json";   // 일출일몰시간조회 (외부 API, No Key Required)

    private final String AI_SERVER_URL = "http://localhost:5000";               // AI 캐스터 및 DJ 서버 URL

    // =========== 메인 통합 조회 메서드 (병렬 처리 적용) ===========
    public WeatherDTO getWeather(int nx, int ny, String areaNo, int stnId, double userLat, double userLon) {
        WeatherDTO dto = new WeatherDTO();

        // 1. 서로 의존성이 없는 외부 API 작업들을 병렬로 시작
        CompletableFuture<Void> forecastTask = CompletableFuture.runAsync(() -> {
            try {
                fetchVilageForecast(dto, nx, ny);       // 1. 단기예보
                // [FIX] 최저(TMN) 또는 최고(TMX) 기온이 누락되었다면, 02:00 기준 데이터로 보완 조회
                if (dto.getTMN() == null || dto.getTMX() == null) {
                    fetchDailyTempRange(dto, nx, ny);   // 보완 로직 호출
                }
            } catch (Exception e) {
                log.error("단기예보 조회 실패", e);
            }
        }, executor);

        // 초단기예보는 단기예보와 별개로 병렬 처리
        CompletableFuture<Void> ultraSrtTask = CompletableFuture.runAsync(() -> {
            try { fetchUltraSrtForecast(dto, nx, ny); } catch (Exception e) { log.error("초단기예보 실패", e); }
        }, executor);

        // 생활기상지수, 꽃가루지수, 일출일몰, 미세먼지, 특보, 지진, 태풍 등도 병렬 처리
        CompletableFuture<Void> livingTask = CompletableFuture.runAsync(() -> fetchLivingWeather(dto, areaNo), executor);
        CompletableFuture<Void> pollenTask = CompletableFuture.runAsync(() -> fetchPollenIndex(dto, areaNo), executor);
        CompletableFuture<Void> sunTask = CompletableFuture.runAsync(() -> fetchSunriseSunset(dto, nx, ny), executor);
        CompletableFuture<Void> dustTask = CompletableFuture.runAsync(() -> fetchFineDust(dto, "서울"), executor);
        CompletableFuture<Void> warnTask = CompletableFuture.runAsync(() -> fetchWeatherWarning(dto, stnId), executor);
        CompletableFuture<Void> earthquakeTask = CompletableFuture.runAsync(() -> fetchEarthquake(dto, userLat, userLon), executor);
        CompletableFuture<Void> typhoonTask = CompletableFuture.runAsync(() -> fetchTyphoon(dto, userLat, userLon), executor);

        // 2. 모든 기본 API 호출이 끝날 때까지 대기 (join)
        // (체감온도 계산이나 AI 브리핑은 기본 날씨 데이터가 필요하므로 이후에 수행)
        CompletableFuture.allOf(
                forecastTask, ultraSrtTask, livingTask, pollenTask,
                sunTask, dustTask, warnTask, earthquakeTask, typhoonTask
        ).join();


        // 3. [Fallback 안전장치]: 분리된 Service를 사용하여 안전하게 AI 기능 호출

        // (1) 체감온도 (Service 호출)
        String sensible = sensibleTempService.getSensibleTemp(dto.getTMP(), dto.getREH(), dto.getWSD());
        dto.setSensibleTemp(sensible);

        // (2) 불쾌지수 (자체 로직 - WeatherService 내부에 유지하거나 별도 유틸로 분리 가능)
        calculateDiscomfortIndex(dto);

        // (3) AI 기능 병렬 호출 (Service 사용)
        CompletableFuture<Void> briefingTask = CompletableFuture.runAsync(() -> {
            String script = briefingService.getBriefing(dto.getTMP(), dto.getSKY(), dto.getPTY(), dto.getPOP());
            dto.setAiBriefing(script);
        }, executor);

        CompletableFuture<Void> djTask = CompletableFuture.runAsync(() -> {
            djService.setMusicRecommendation(dto);
        }, executor);

        // (4) 옷차림 (이미 Service 사용 중)
        String recommendation = clothingService.recommendOutfit(dto.getTMP(), dto.getPTY(), dto.getWSD());
        String icon = clothingService.getOutfitIcon(dto.getTMP());
        dto.setClothingRecommendation(recommendation);
        dto.setOutfitIcon(icon);

        selectBgImage(dto); // 배경 이미지 선택 로직

        CompletableFuture.allOf(briefingTask, djTask).join(); // AI 작업 완료 대기

        return dto; // 최종 결과 반환
    }

    // ================= 일출/일몰 시간 조회 및 태양/달 진행도 계산 로직 =================
    private void fetchSunriseSunset(WeatherDTO dto, int nx, int ny) {
        try {
            double[] gps = convertGridToGps(nx, ny);
            double lat = gps[0];
            double lng = gps[1];

            // 오늘 날짜 기준 API 호출
            URI uri = UriComponentsBuilder.fromUriString(URL_SUNRISE)
                    .queryParam("lat", lat)
                    .queryParam("lng", lng)
                    .queryParam("formatted", "0")
                    .queryParam("date", "today")
                    .build()
                    .toUri();

            String json = new RestTemplate().getForObject(uri, String.class);
            JsonNode root = mapper.readTree(json);

            if (!"OK".equals(root.path("status").asText())) return;

            JsonNode results = root.path("results");
            String sunriseUtc = results.path("sunrise").asText();
            String sunsetUtc = results.path("sunset").asText();

            // ZonedDateTime을 사용하여 시간대 변환 (UTC -> KST)
            ZonedDateTime sunriseZoned = ZonedDateTime.parse(sunriseUtc, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                    .withZoneSameInstant(ZoneId.of("Asia/Seoul"));
            ZonedDateTime sunsetZoned = ZonedDateTime.parse(sunsetUtc, DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                    .withZoneSameInstant(ZoneId.of("Asia/Seoul"));

            dto.setSunrise(sunriseZoned.format(DateTimeFormatter.ofPattern("HH:mm")));
            dto.setSunset(sunsetZoned.format(DateTimeFormatter.ofPattern("HH:mm")));

            // 현재 시간
            LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Seoul"));
            LocalDateTime sunriseTime = sunriseZoned.toLocalDateTime();
            LocalDateTime sunsetTime = sunsetZoned.toLocalDateTime();

            // 낮/밤 판별
            boolean isDay = now.isAfter(sunriseTime) && now.isBefore(sunsetTime);
            dto.setDayTime(isDay);

            if (isDay) {
                // [낮] Sun Cycle: 일출 ~ 일몰
                long totalDaySeconds = ChronoUnit.SECONDS.between(sunriseTime, sunsetTime);
                long currentSeconds = ChronoUnit.SECONDS.between(sunriseTime, now);
                double progress = (double) currentSeconds / totalDaySeconds * 100.0;
                dto.setSunProgress(Math.min(Math.max(progress, 0), 100));
            } else {
                // [밤] Moon Cycle: 일몰 ~ 다음날 일출
                LocalDateTime moonStart;
                LocalDateTime moonEnd;

                if (now.isBefore(sunriseTime)) {
                    moonStart = sunsetTime.minusDays(1);
                    moonEnd = sunriseTime;
                } else {
                    moonStart = sunsetTime;
                    moonEnd = sunriseTime.plusDays(1);
                }

                long totalNightSeconds = ChronoUnit.SECONDS.between(moonStart, moonEnd);
                long currentNightSeconds = ChronoUnit.SECONDS.between(moonStart, now);
                double progress = (double) currentNightSeconds / totalNightSeconds * 100.0;
                dto.setSunProgress(Math.min(Math.max(progress, 0), 100));
                dto.setMoonPhase("Moon Night");
            }

        } catch (Exception e) {
            log.warn("일출/일몰 조회 실패: {}", e.getMessage());
            dto.setSunrise("06:00");
            dto.setSunset("19:30");
            dto.setSunProgress(50);
            dto.setDayTime(true);
        }
    }

    // ================= 격자 좌표를 위도/경도로 변환하는 메서드 =================
    private double[] convertGridToGps(int nx, int ny) {
        double RE = 6371.00877; // 지구 반경(km)
        double GRID = 5.0;      // 격자 간격(km)
        double SLAT1 = 30.0;    // 투영 위도1(degree)
        double SLAT2 = 60.0;    // 투영 위도2(degree)
        double OLON = 126.0;    // 기준점 경도(degree)
        double OLAT = 38.0;     // 기준점 위도(degree)
        double XO = 43;         // 기준점 X좌표(GRID)
        double YO = 136;        // 기준점 Y좌표(GRID)

        double DEGRAD = Math.PI / 180.0; // 파이 / 180도
        double RADDEG = 180.0 / Math.PI; // 180도 / 파이

        double re = RE / GRID;          // 축척재표시
        double slat1 = SLAT1 * DEGRAD;  // 투영 위도 1 라디안
        double slat2 = SLAT2 * DEGRAD;  // 투영 위도 2 라디안
        double olon = OLON * DEGRAD;    // 기준점 경도 라디안
        double olat = OLAT * DEGRAD;    // 기준점 위도 라디안

        double sn = Math.tan(Math.PI * 0.25 + slat2 * 0.5) / Math.tan(Math.PI * 0.25 + slat1 * 0.5);
        sn = Math.log(Math.cos(slat1) / Math.cos(slat2)) / Math.log(sn);
        double sf = Math.tan(Math.PI * 0.25 + slat1 * 0.5);
        sf = Math.pow(sf, sn) * Math.cos(slat1) / sn;
        double ro = Math.tan(Math.PI * 0.25 + olat * 0.5);
        ro = re * sf / Math.pow(ro, sn);

        double xn = nx - XO;
        double yn = ro - ny + YO;
        double ra = Math.sqrt(xn * xn + yn * yn);
        if (sn < 0.0) ra = -ra;
        double alat = Math.pow((re * sf / ra), (1.0 / sn));
        alat = 2.0 * Math.atan(alat) - Math.PI * 0.5;

        double theta;
        if (Math.abs(xn) <= 0.0) theta = 0.0;
        else {
            if (Math.abs(yn) <= 0.0) {
                theta = Math.PI * 0.5;
                if (xn < 0.0) theta = -theta;
            } else theta = Math.atan2(xn, yn);
        }
        double alon = theta / sn + olon;
        double lat = alat * RADDEG;
        double lon = alon * RADDEG;

        return new double[]{lat, lon};
    }


    // ================= 꽃가루 지수 조회 및 코멘트 생성 로직 =================
    private void fetchPollenIndex(WeatherDTO dto, String areaNo) {
        String safeAreaNo = (areaNo == null || areaNo.length() != 10) ? "1100000000" : areaNo;
        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Seoul"));
        String requestTime;
        if (now.getHour() < 6) requestTime = now.minusDays(1).format(DateTimeFormatter.ofPattern("yyyyMMdd18"));
        else if (now.getHour() < 18) requestTime = now.format(DateTimeFormatter.ofPattern("yyyyMMdd06"));
        else requestTime = now.format(DateTimeFormatter.ofPattern("yyyyMMdd18"));

        int month = now.getMonthValue();
        boolean isSpring = (month >= 4 && month <= 6);
        boolean isAutumn = (month >= 8 && month <= 10);

        try {
            if (isSpring) {
                String oakVal = callPollenApi(URL_POLLEN_OAK, safeAreaNo, requestTime);
                dto.setOakPollenRisk(oakVal);
                String pineVal = callPollenApi(URL_POLLEN_PINE, safeAreaNo, requestTime);
                dto.setPinePollenRisk(pineVal);
            }
            if (isAutumn) {
                String weedsVal = callPollenApi(URL_POLLEN_WEEDS, safeAreaNo, requestTime);
                dto.setWeedsPollenRisk(weedsVal);
            }
            generatePollenComment(dto);
        } catch (Exception e) {
            log.warn("꽃가루 지수 조회 실패: {}", e.getMessage());
            dto.setPollenComment("꽃가루 정보를 불러올 수 없습니다.");
        }
    }


    // ================= 꽃가루 지수 API 호출 헬퍼 메서드 =================
    private String callPollenApi(String url, String areaNo, String time) {
        try {
            URI uri = UriComponentsBuilder.fromUriString(url)
                    .queryParam("serviceKey", API_KEY)
                    .queryParam("pageNo", "1")
                    .queryParam("numOfRows", "10")
                    .queryParam("dataType", "JSON")
                    .queryParam("areaNo", areaNo)
                    .queryParam("time", time)
                    .build()
                    .toUri();

            String json = new RestTemplate().getForObject(uri, String.class);
            JsonNode root = mapper.readTree(json);
            if (!"00".equals(root.path("response").path("header").path("resultCode").asText())) return null;
            JsonNode items = root.path("response").path("body").path("items").path("item");
            if (items.isEmpty()) return null;
            return items.get(0).path("h0").asText();
        } catch (Exception e) {
            return null;
        }
    }

    // ================= 꽃가루 위험도에 따른 코멘트 생성 =================
    private void generatePollenComment(WeatherDTO dto) {
        String oak = dto.getOakPollenRisk();
        String pine = dto.getPinePollenRisk();
        String weeds = dto.getWeedsPollenRisk();

        int maxRisk = 0;
        String type = "";

        if (oak != null) { try { int val = Integer.parseInt(oak); if(val > maxRisk) { maxRisk = val; type = "참나무"; } } catch(Exception e){} }
        if (pine != null) { try { int val = Integer.parseInt(pine); if(val > maxRisk) { maxRisk = val; type = "소나무"; } } catch(Exception e){} }
        if (weeds != null) { try { int val = Integer.parseInt(weeds); if(val > maxRisk) { maxRisk = val; type = "잡초류"; } } catch(Exception e){} }

        if (maxRisk == 0) dto.setPollenComment("꽃가루 위험이 없습니다.");
        else if (maxRisk == 1) dto.setPollenComment("꽃가루 농도가 낮습니다.");
        else if (maxRisk == 2) dto.setPollenComment(type + " 꽃가루가 날릴 수 있습니다. 환기에 주의하세요.");
        else if (maxRisk >= 3) dto.setPollenComment("🚨 " + type + " 꽃가루 농도 위험! 마스크를 꼭 착용하세요.");
        else dto.setPollenComment("제공 기간이 아닙니다.");
    }

    // ================= 체감온도(AI 예측) 조회 로직 =================
    private void fetchSensibleTemp(WeatherDTO dto) {
        try {
            String tmpStr = dto.getTMP();
            String rehStr = dto.getREH();
            String wsdStr = dto.getWSD();

            if (tmpStr != null && rehStr != null && wsdStr != null) {
                RestTemplate restTemplate = new RestTemplate();
                Map<String, Object> requestBody = new HashMap<>();
                requestBody.put("temp", Double.parseDouble(tmpStr));
                requestBody.put("hum", Double.parseDouble(rehStr));
                requestBody.put("wind", Double.parseDouble(wsdStr));

                @SuppressWarnings("unchecked")
                Map<String, Double> response = restTemplate.postForObject(
                        AI_SERVER_URL + "/sensible",
                        requestBody,
                        Map.class
                );

                if (response != null && response.containsKey("sensible_temp")) {
                    dto.setSensibleTemp(String.valueOf(response.get("sensible_temp")));
                } else {
                    dto.setSensibleTemp(dto.getTMP());
                }
            } else {
                dto.setSensibleTemp("-");
            }
        } catch (Exception e) {
            log.warn("체감온도 AI 예측 실패: {}", e.getMessage());
            dto.setSensibleTemp(dto.getTMP());
        }
    }

    // ================= 불쾌지수(DI) 계산 로직 =================
    private void calculateDiscomfortIndex(WeatherDTO dto) {
        try {
            if (dto.getTMP() == null || dto.getREH() == null) return;
            double t = Double.parseDouble(dto.getTMP());
            double h = Double.parseDouble(dto.getREH());
            double di = 0.81 * t + 0.01 * h * (0.99 * t - 14.3) + 46.3;
            dto.setDiscomfortIndex(String.format("%.1f", di));

            if (di >= 80) {
                dto.setDiscomfortStage("매우높음");
                dto.setDiscomfortComment("전원 불쾌감을 느낍니다. 다툼 주의! 🤬");
            } else if (di >= 75) {
                dto.setDiscomfortStage("높음");
                dto.setDiscomfortComment("50% 정도 불쾌감을 느낍니다. 😓");
            } else if (di >= 68) {
                dto.setDiscomfortStage("보통");
                dto.setDiscomfortComment("불쾌감이 나타나기 시작합니다. 😐");
            } else {
                dto.setDiscomfortStage("낮음");
                dto.setDiscomfortComment("쾌적한 날씨입니다. 상쾌해요! 😄");
            }
        } catch (Exception e) {
            log.warn("불쾌지수 계산 실패");
            dto.setDiscomfortStage("-");
        }
    }

    // ================= 일일 최저/최고 기온 보완 로직 =================
    private void fetchDailyTempRange(WeatherDTO dto, int nx, int ny) {
        try {
            String baseDate = LocalDateTime.now(ZoneId.of("Asia/Seoul")).format(DateTimeFormatter.ofPattern("yyyyMMdd"));
            String baseTime = "0200";

            URI uri = buildUri(URL_VILAGE, baseDate, baseTime, nx, ny);
            String json = new RestTemplate().getForObject(uri, String.class);
            JsonNode root = mapper.readTree(json);

            if (!"00".equals(root.path("response").path("header").path("resultCode").asText())) return;

            JsonNode items = root.path("response").path("body").path("items").path("item");
            for (JsonNode item : items) {
                String category = item.path("category").asText();
                String fcstDate = item.path("fcstDate").asText();
                String value = item.path("fcstValue").asText();

                if (fcstDate.equals(baseDate)) {
                    if ("TMN".equals(category) && dto.getTMN() == null) dto.setTMN(value);
                    if ("TMX".equals(category) && dto.getTMX() == null) dto.setTMX(value);
                }
            }
        } catch (Exception e) {
            log.warn("일일 기온 범위 보완 조회 실패: {}", e.getMessage());
        }
    }

    // ================= 지진 거리 계산 및 안전 분석 =================
    private void fetchEarthquake(WeatherDTO dto, double userLat, double userLon) {
        try {
            LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Seoul"));
            String toDate = now.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
            String fromDate = now.minusDays(7).format(DateTimeFormatter.ofPattern("yyyyMMdd"));

            URI uri = UriComponentsBuilder.fromUriString(URL_EQK)
                    .queryParam("serviceKey", API_KEY)
                    .queryParam("pageNo", "1")
                    .queryParam("numOfRows", "1")
                    .queryParam("dataType", "JSON")
                    .queryParam("fromTmFc", fromDate)
                    .queryParam("toTmFc", toDate)
                    .build()
                    .toUri();

            String json = new RestTemplate().getForObject(uri, String.class);
            JsonNode root = mapper.readTree(json);

            if (!"00".equals(root.path("response").path("header").path("resultCode").asText())) return;

            JsonNode items = root.path("response").path("body").path("items").path("item");
            if (items.isEmpty()) {
                dto.setHasEarthquake(false);
                return;
            }

            JsonNode item = items.get(0);
            dto.setHasEarthquake(true);
            dto.setEqTime(formatEqTime(item.path("tmFc").asText()));
            dto.setEqLoc(item.path("loc").asText());
            dto.setEqMag(item.path("mt").asText());

            try {
                double eqLat = Double.parseDouble(item.path("lat").asText("0"));
                double eqLon = Double.parseDouble(item.path("lon").asText("0"));

                if (eqLat != 0 && eqLon != 0) {
                    double dist = calculateDistance(userLat, userLon, eqLat, eqLon);
                    dto.setEqDist(String.format("%.1fkm", dist));
                    double mag = Double.parseDouble(dto.getEqMag());
                    dto.setEqSafetyMsg(analyzeEarthquakeSafety(mag, dist));
                } else {
                    dto.setEqDist("위치불명");
                    dto.setEqSafetyMsg("지진 위치 정보가 정확하지 않습니다.");
                }
            } catch (NumberFormatException e) {
                dto.setEqDist("-");
                dto.setEqSafetyMsg("데이터 분석 중 오류 발생");
            }
        } catch (Exception e) {
            log.error("지진 정보 조회 실패", e);
            dto.setHasEarthquake(false);
        }
    }

    // =================  지진 발생 시간 포맷팅 헬퍼 메서드 =================
    private String analyzeEarthquakeSafety(double magnitude, double distanceKm) {
        if (distanceKm > 500) return "거리가 멀어 영향이 거의 없습니다. 안심하세요.";
        if (magnitude >= 5.0) {
            if (distanceKm < 100) return "🚨 위험! 낙하물에 주의하고 즉시 안전한 곳으로 대피하세요.";
            else return "진동이 느껴질 수 있습니다. 뉴스를 주시하세요.";
        } else if (magnitude >= 3.0) {
            if (distanceKm < 50) return "건물이 흔들릴 수 있습니다. 주의가 필요합니다.";
            else return "민감한 분들은 진동을 느낄 수 있습니다.";
        } else {
            return "규모가 작아 별다른 피해는 없을 것으로 예상됩니다.";
        }
    }

    // ================= 태풍 거리 계산 및 안전 분석 =================
    private void fetchTyphoon(WeatherDTO dto, double userLat, double userLon) {
        try {
            LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Seoul"));
            String toDate = now.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
            String fromDate = now.minusDays(5).format(DateTimeFormatter.ofPattern("yyyyMMdd"));

            URI uri = UriComponentsBuilder.fromUriString(URL_TYPHOON)
                    .queryParam("serviceKey", API_KEY)
                    .queryParam("pageNo", "1")
                    .queryParam("numOfRows", "1")
                    .queryParam("dataType", "JSON")
                    .queryParam("fromTmFc", fromDate)
                    .queryParam("toTmFc", toDate)
                    .build()
                    .toUri();

            String json = new RestTemplate().getForObject(uri, String.class);
            JsonNode root = mapper.readTree(json);
            if (!"00".equals(root.path("response").path("header").path("resultCode").asText())) return;

            JsonNode items = root.path("response").path("body").path("items").path("item");
            if (items.isEmpty()) {
                dto.setHasTyphoon(false);
                return;
            }

            JsonNode item = items.get(0);
            dto.setHasTyphoon(true);
            dto.setTyphoonName("제" + item.path("typSeq").asText() + "호 " + item.path("typName").asText());
            dto.setTyphoonTime(formatEqTime(item.path("tmFc").asText()));
            dto.setTyphoonStatus("현재 활동 중 (" + item.path("typLoc").asText() + ")");

            try {
                double typLat = Double.parseDouble(item.path("lat").asText("0"));
                double typLon = Double.parseDouble(item.path("lon").asText("0"));
                String speedStr = item.path("typWs").asText("0").replaceAll("[^0-9.]", "");
                double windSpeed = Double.parseDouble(speedStr);

                if (typLat != 0 && typLon != 0) {
                    double dist = calculateDistance(userLat, userLon, typLat, typLon);
                    dto.setTyphoonDist(String.format("%.0fkm", dist));
                    dto.setTyphoonSafetyMsg(analyzeTyphoonSafety(windSpeed, dist));
                }
            } catch (Exception e) {
                dto.setTyphoonDist("-");
                dto.setTyphoonSafetyMsg("경로 분석 중...");
            }
        } catch (Exception e) {
            log.error("태풍 정보 조회 실패", e);
            dto.setHasTyphoon(false);
        }
    }

    // =================  태풍 안전 분석 헬퍼 메서드 =================
    private String analyzeTyphoonSafety(double windSpeed, double distanceKm) {
        if (distanceKm > 800) return "아직 거리가 멉니다. 태풍 정보를 주시하세요.";
        if (distanceKm < 300) {
            if (windSpeed > 30) return "🚨 태풍의 직접 영향권입니다! 외출을 자제하세요.";
            else return "태풍이 접근 중입니다. 비바람에 주의하세요.";
        } else {
            return "태풍의 간접 영향이 있을 수 있습니다. 우산을 챙기세요.";
        }
    }

    // ================= 두 지점 간 거리 계산 헬퍼 메서드 =================
    private double calculateDistance(double lat1, double lon1, double lat2, double lon2) {
        final int R = 6371;
        double latDistance = Math.toRadians(lat2 - lat1);
        double lonDistance = Math.toRadians(lon2 - lon1);
        double a = Math.sin(latDistance / 2) * Math.sin(latDistance / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(lonDistance / 2) * Math.sin(lonDistance / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return R * c;
    }

     // ================= 미세먼지 정보 조회 및 코멘트 생성 로직 =================
    private void fetchFineDust(WeatherDTO dto, String sidoName) {
        try {
            URI uri = UriComponentsBuilder.fromUriString(URL_DUST)
                    .queryParam("serviceKey", API_KEY)
                    .queryParam("returnType", "json")
                    .queryParam("numOfRows", "1")
                    .queryParam("pageNo", "1")
                    .queryParam("sidoName", URLEncoder.encode(sidoName, StandardCharsets.UTF_8))
                    .queryParam("ver", "1.0")
                    .build(true)
                    .toUri();

            String json = new RestTemplate().getForObject(uri, String.class);
            JsonNode root = mapper.readTree(json);
            if (!"00".equals(root.path("response").path("header").path("resultCode").asText())) return;
            JsonNode items = root.path("response").path("body").path("items");
            if (items.isEmpty()) return;

            JsonNode item = items.get(0);
            dto.setPm10Value(item.path("pm10Value").asText("-"));
            dto.setPm10Grade(item.path("pm10Grade").asText("0"));
            dto.setPm25Value(item.path("pm25Value").asText("-"));
            dto.setPm25Grade(item.path("pm25Grade").asText("0"));
            dto.setKhaiGrade(item.path("khaiGrade").asText("0"));

            String grade = dto.getPm10Grade();
            if ("1".equals(grade)) dto.setDustComment("공기가 상쾌해요! 환기하세요.");
            else if ("2".equals(grade)) dto.setDustComment("평범한 대기질입니다.");
            else if ("3".equals(grade)) dto.setDustComment("미세먼지 나쁨. 마스크 필수!");
            else if ("4".equals(grade)) dto.setDustComment("최악의 공기. 외출 자제!");
            else dto.setDustComment("미세먼지 측정 중...");
        } catch (Exception e) {
            log.error("미세먼지 조회 실패", e);
            dto.setDustComment("정보 연동 실패");
        }
    }

    // ================= AI DJ 음악 추천 로직 =================
    private void fetchAiDj(WeatherDTO dto) {
        String fallbackVideoId = "5qap5aO4i9A";
        String fallbackComment = "편안한 음악을 준비했습니다. (AI 연결 대기중 🎧)";

        dto.setYoutubeVideoId(fallbackVideoId);
        dto.setMusicComment(fallbackComment);

        try {
            RestTemplate restTemplate = new RestTemplate();
            int currentHour = LocalDateTime.now(ZoneId.of("Asia/Seoul")).getHour();

            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("pty", dto.getPTY() != null ? dto.getPTY() : "0");
            requestBody.put("sky", dto.getSKY() != null ? dto.getSKY() : "맑음");
            requestBody.put("hour", currentHour);

            @SuppressWarnings("unchecked")
            Map<String, String> response = restTemplate.postForObject(
                    AI_SERVER_URL + "/dj",
                    requestBody,
                    Map.class
            );

            if (response != null) {
                String videoId = response.get("videoId");
                String comment = response.get("comment");
                if (videoId != null && !videoId.isEmpty()) dto.setYoutubeVideoId(videoId);
                if (comment != null && !comment.isEmpty()) dto.setMusicComment(comment);
            }
        } catch (Exception e) {
            log.warn("AI DJ 연결 실패: {}", e.getMessage());
        }
    }

    // ================= 배경 이미지 선택 로직 =================
    private void selectBgImage(WeatherDTO dto) {
        int hour = LocalDateTime.now(ZoneId.of("Asia/Seoul")).getHour();
        boolean isNight = (hour >= 19 || hour <= 6);
        String pty = dto.getPTY();
        String sky = dto.getSKY();

        String imageUrl = "https://images.unsplash.com/photo-1622396481328-9b1b78cdd9fd?q=80&w=1974&auto=format&fit=crop";

        if (pty != null && (pty.equals("비") || pty.equals("비/눈") || pty.equals("소나기") || pty.equals("빗방울"))) {
            if (isNight) imageUrl = "https://images.unsplash.com/photo-1702898044318-573fddbea718?q=80&w=1170&auto=format&fit=crop";
            else imageUrl = "https://images.unsplash.com/photo-1655271528290-864e38f715d8?q=80&w=1170&auto=format&fit=crop";
        } else if (pty != null && (pty.equals("눈") || pty.equals("진눈깨비") || pty.equals("눈날림"))) {
            if (isNight) imageUrl = "https://images.unsplash.com/photo-1519692933481-e162a57d6721?q=80&w=2070&auto=format&fit=crop";
            else imageUrl = "https://images.unsplash.com/photo-1705989277853-e146af1d029a?q=80&w=735&auto=format&fit=crop";
        } else {
            if (isNight) {
                if (sky != null && (sky.equals("흐림") || sky.equals("구름많음")))
                    imageUrl = "https://images.unsplash.com/photo-1532349150739-cb439f9a34a3?q=80&w=1170&auto=format&fit=crop";
                else
                    imageUrl = "https://images.unsplash.com/photo-1509773896068-7fd415d91e2e?q=80&w=2069&auto=format&fit=crop";
            } else {
                if (sky != null && sky.equals("흐림"))
                    imageUrl = "https://images.unsplash.com/photo-1496285181113-d59aaf3ea20f?q=80&w=1170&auto=format&fit=crop";
                else if (sky != null && sky.equals("구름많음"))
                    imageUrl = "https://images.unsplash.com/photo-1501630834273-4b5604d2ee31?q=80&w=1170&auto=format&fit=crop";
                else
                    imageUrl = "https://images.unsplash.com/photo-1601297183305-6df142704ea2?q=80&w=1074&auto=format&fit=crop";
            }
        }
        dto.setBgImageUrl(imageUrl);
    }

    // ================= AI 캐스터 브리핑 로직 =================
    private void fetchAiBriefing(WeatherDTO dto) {
        try {
            RestTemplate restTemplate = new RestTemplate();
            Map<String, Object> requestBody = new HashMap<>();
            requestBody.put("temp", dto.getTMP());
            requestBody.put("sky", dto.getSKY());
            requestBody.put("pty", dto.getPTY());
            requestBody.put("pop", dto.getPOP());

            @SuppressWarnings("unchecked")
            Map<String, String> response = restTemplate.postForObject(
                    AI_SERVER_URL + "/briefing",
                    requestBody,
                    Map.class
            );

            if (response != null && response.containsKey("script")) {
                dto.setAiBriefing(response.get("script"));
            } else {
                dto.setAiBriefing("AI 캐스터가 잠시 휴식 중입니다. (응답 없음)");
            }
        } catch (Exception e) {
            log.warn("AI 캐스터 서버 연결 실패: {}", e.getMessage());
            dto.setAiBriefing("AI 캐스터 연결에 실패했습니다. (서버 확인 필요)");
        }
    }

    // ================= 지진/태풍 목록 조회 로직 =================
    public List<EarthquakeDTO> getEarthquakeList() {
        List<EarthquakeDTO> list = new ArrayList<>();
        try {
            LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Seoul"));
            String toDate = now.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
            String fromDate = now.minusDays(30).format(DateTimeFormatter.ofPattern("yyyyMMdd"));

            URI uri = UriComponentsBuilder.fromUriString(URL_EQK)
                    .queryParam("serviceKey", API_KEY)
                    .queryParam("pageNo", "1")
                    .queryParam("numOfRows", "100")
                    .queryParam("dataType", "JSON")
                    .queryParam("fromTmFc", fromDate)
                    .queryParam("toTmFc", toDate)
                    .build()
                    .toUri();

            String json = new RestTemplate().getForObject(uri, String.class);
            JsonNode root = mapper.readTree(json);
            if (!"00".equals(root.path("response").path("header").path("resultCode").asText())) return list;

            JsonNode items = root.path("response").path("body").path("items").path("item");
            for (JsonNode item : items) {
                EarthquakeDTO dto = new EarthquakeDTO();
                dto.setTmFc(formatEqTime(item.path("tmFc").asText()));
                dto.setTmEqk(formatEqTime(item.path("tmEqk").asText()));
                dto.setLat(item.path("lat").asText());
                dto.setLon(item.path("lon").asText());
                dto.setLoc(item.path("loc").asText());
                dto.setMt(item.path("mt").asText());
                dto.setRem(item.path("rem").asText());
                dto.setImg(item.path("img").asText());
                list.add(dto);
            }
        } catch (Exception e) {
            log.error("지진 목록 조회 실패", e);
        }
        return list;
    }

    // ================= 태풍 목록 조회 로직 =================
    public List<TyphoonDTO> getTyphoonList() {
        List<TyphoonDTO> list = new ArrayList<>();
        try {
            LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Seoul"));
            String toDate = now.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
            String fromDate = now.minusDays(30).format(DateTimeFormatter.ofPattern("yyyyMMdd"));

            URI uri = UriComponentsBuilder.fromUriString(URL_TYPHOON)
                    .queryParam("serviceKey", API_KEY)
                    .queryParam("pageNo", "1")
                    .queryParam("numOfRows", "100")
                    .queryParam("dataType", "JSON")
                    .queryParam("fromTmFc", fromDate)
                    .queryParam("toTmFc", toDate)
                    .build()
                    .toUri();

            String json = new RestTemplate().getForObject(uri, String.class);
            JsonNode root = mapper.readTree(json);
            if (!"00".equals(root.path("response").path("header").path("resultCode").asText())) return list;

            JsonNode items = root.path("response").path("body").path("items").path("item");
            for (JsonNode item : items) {
                TyphoonDTO dto = new TyphoonDTO();
                dto.setTmFc(formatEqTime(item.path("tmFc").asText()));
                dto.setTypSeq(item.path("typSeq").asText());
                dto.setTypName(item.path("typName").asText());
                dto.setTypEn(item.path("typEn").asText());
                dto.setManFc(item.path("manFc").asText());
                dto.setLoc(item.path("typLoc").asText("-"));
                dto.setLat(item.path("lat").asText("0"));
                dto.setLon(item.path("lon").asText("0"));
                dto.setDir(item.path("typDir").asText("-"));
                dto.setSp(item.path("typSp").asText("-"));
                dto.setPs(item.path("typPs").asText("-"));
                dto.setWs(item.path("typWs").asText("-"));
                list.add(dto);
            }
        } catch (Exception e) {
            log.error("태풍 목록 조회 실패", e);
        }
        return list;
    }

    // ================= 기상특보 조회 로직 =================
    private void fetchWeatherWarning(WeatherDTO dto, int stnId) {
        try {
            URI uri = UriComponentsBuilder.fromUriString(URL_WARN)
                    .queryParam("serviceKey", API_KEY)
                    .queryParam("pageNo", "1")
                    .queryParam("numOfRows", "5")
                    .queryParam("dataType", "JSON")
                    .queryParam("stnId", stnId)
                    .build()
                    .toUri();

            String json = new RestTemplate().getForObject(uri, String.class);
            JsonNode root = mapper.readTree(json);

            if (!"00".equals(root.path("response").path("header").path("resultCode").asText())) return;

            JsonNode items = root.path("response").path("body").path("items").path("item");
            if (items.isEmpty()) {
                dto.setHasWarning(false);
                return;
            }

            JsonNode item = items.get(0);
            String title = item.path("title").asText();
            if (title.contains("해제") || title.contains("종료")) {
                dto.setHasWarning(false);
                return;
            }

            String content = item.path("t1").asText();
            if (content != null && !content.isEmpty()) {
                dto.setHasWarning(true);
                dto.setWarningMsg(content);
            } else {
                dto.setHasWarning(false);
            }
        } catch (Exception e) {
            log.error("기상특보 조회 실패", e);
            dto.setHasWarning(false);
        }
    }

    // ================= 생활지수(자외선 지수) 조회 로직 =================
    private void fetchLivingWeather(WeatherDTO dto, String areaNo) {
        try {
            String safeAreaNo = (areaNo == null || areaNo.length() != 10) ? "1100000000" : areaNo;
            LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Seoul"));
            String requestTime;

            if (now.getHour() < 6) requestTime = now.minusDays(1).format(DateTimeFormatter.ofPattern("yyyyMMdd18"));
            else if (now.getHour() < 18) requestTime = now.format(DateTimeFormatter.ofPattern("yyyyMMdd06"));
            else requestTime = now.format(DateTimeFormatter.ofPattern("yyyyMMdd18"));

            URI uvUri = buildLivingUri(URL_UV, safeAreaNo, requestTime);
            String uvJson = new RestTemplate().getForObject(uvUri, String.class);
            parseLivingJson(dto, uvJson, "UV");

        } catch (Exception e) {
            log.error("생활지수 조회 실패", e);
            dto.setUvStage("정보없음");
            dto.setUvIndex("0");
            dto.setUvComment("정보를 불러올 수 없습니다.");
        }
    }

    // =================  생활지수 API URI 빌더 헬퍼 메서드 =================
    private URI buildLivingUri(String url, String areaNo, String time) {
        return UriComponentsBuilder.fromUriString(url)
                .queryParam("serviceKey", API_KEY)
                .queryParam("pageNo", "1")
                .queryParam("numOfRows", "10")
                .queryParam("dataType", "JSON")
                .queryParam("areaNo", areaNo)
                .queryParam("time", time)
                .build()
                .toUri();
    }

    // =================  생활지수 JSON 파싱 헬퍼 메서드 =================
    private void parseLivingJson(WeatherDTO dto, String json, String type) throws Exception {
        JsonNode root = mapper.readTree(json);
        String resultCode = root.path("response").path("header").path("resultCode").asText();
        if (!"00".equals(resultCode)) return;

        JsonNode items = root.path("response").path("body").path("items").path("item");
        if (items.isEmpty()) return;

        JsonNode item = items.get(0);
        String h0 = item.path("h0").asText();
        if (h0 == null || h0.isEmpty()) h0 = "0";
        int value = Integer.parseInt(h0);

        if (type.equals("UV")) {
            dto.setUvIndex(h0);
            if (value <= 2) { dto.setUvStage("낮음"); dto.setUvComment("자외선 걱정 없이 야외활동 가능해요."); }
            else if (value <= 5) { dto.setUvStage("보통"); dto.setUvComment("외출 시 선글라스나 모자를 쓰면 좋아요."); }
            else if (value <= 7) { dto.setUvStage("높음"); dto.setUvComment("낮 시간대에는 그늘에 머무르세요."); }
            else if (value <= 10) { dto.setUvStage("매우높음"); dto.setUvComment("외출을 피하고 자외선 차단제를 꼼꼼히!"); }
            else { dto.setUvStage("위험"); dto.setUvComment("가능하면 실내에 머무르는 게 좋습니다."); }
        }
    }

    // ================= 단기예보 및 동네예보 조회 로직 =================
    private void fetchVilageForecast(WeatherDTO dto, int nx, int ny) throws Exception {
        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Seoul"));
        if (now.getMinute() < 10) now = now.minusHours(1);

        int hour = now.getHour();
        String baseTime;
        String baseDate = now.format(DateTimeFormatter.ofPattern("yyyyMMdd"));

        if (hour < 2) { baseTime = "2300"; baseDate = now.minusDays(1).format(DateTimeFormatter.ofPattern("yyyyMMdd")); }
        else if (hour < 5) baseTime = "0200";
        else if (hour < 8) baseTime = "0500";
        else if (hour < 11) baseTime = "0800";
        else if (hour < 14) baseTime = "1100";
        else if (hour < 17) baseTime = "1400";
        else if (hour < 20) baseTime = "1700";
        else if (hour < 23) baseTime = "2000";
        else baseTime = "2300";

        URI uri = buildUri(URL_VILAGE, baseDate, baseTime, nx, ny);
        String json = new RestTemplate().getForObject(uri, String.class);
        parseVilageJson(dto, json, baseDate, baseTime);
    }

    // ================= 단기예보 조회 로직 =================
    private void fetchUltraSrtForecast(WeatherDTO dto, int nx, int ny) throws Exception {
        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Seoul"));
        if (now.getMinute() < 45) now = now.minusHours(1);

        String baseDate = now.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String baseTime = now.format(DateTimeFormatter.ofPattern("HH30"));

        URI uri = buildUri(URL_ULTRA, baseDate, baseTime, nx, ny);
        String json = new RestTemplate().getForObject(uri, String.class);
        JsonNode root = mapper.readTree(json);

        if (!"00".equals(root.path("response").path("header").path("resultCode").asText())) return;

        JsonNode items = root.path("response").path("body").path("items").path("item");
        Map<String, WeatherDTO.ShortTermForecast> forecastMap = new TreeMap<>();

        for (JsonNode item : items) {
            String fcstTime = item.path("fcstTime").asText();
            String category = item.path("category").asText();
            String value = item.path("fcstValue").asText();

            forecastMap.putIfAbsent(fcstTime, new WeatherDTO.ShortTermForecast());
            WeatherDTO.ShortTermForecast forecast = forecastMap.get(fcstTime);
            forecast.setFcstTime(fcstTime);

            switch (category) {
                case "T1H": forecast.setT1H(value); break;
                case "RN1": forecast.setRN1(value); break;
                case "SKY": forecast.setSKY(value); break;
                case "PTY": forecast.setPTY(value); break;
                case "LGT": forecast.setLGT(value); break;
                case "REH": forecast.setREH(value); break;
                case "WSD": forecast.setWSD(value); break;
            }
        }
        ArrayList<WeatherDTO.ShortTermForecast> list = new ArrayList<>(forecastMap.values());
        Collections.sort(list);
        dto.setShortTermForecasts(list);
    }

    // =================  단기예보/동네예보 API URI 빌더 헬퍼 메서드 =================
    private URI buildUri(String url, String baseDate, String baseTime, int nx, int ny) {
        return UriComponentsBuilder.fromUriString(url)
                .queryParam("serviceKey", API_KEY)
                .queryParam("pageNo", "1")
                .queryParam("numOfRows", "1000")
                .queryParam("dataType", "JSON")
                .queryParam("base_date", baseDate)
                .queryParam("base_time", baseTime)
                .queryParam("nx", nx)
                .queryParam("ny", ny)
                .build()
                .toUri();
    }

    // =================  동네예보 JSON 파싱 헬퍼 메서드 =================
    private void parseVilageJson(WeatherDTO dto, String json, String baseDate, String baseTime) throws Exception {
        JsonNode root = mapper.readTree(json);
        if (!"00".equals(root.path("response").path("header").path("resultCode").asText())) return;

        JsonNode items = root.path("response").path("body").path("items").path("item");
        dto.setBaseDate(baseDate);
        dto.setBaseTime(baseTime);

        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Seoul"));
        String tomorrowDate = now.plusDays(1).format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String dayAfterTomorrowDate = now.plusDays(2).format(DateTimeFormatter.ofPattern("yyyyMMdd"));

        dto.setTomorrowFcstDate(tomorrowDate);
        dto.setDayAfterTomorrowFcstDate(dayAfterTomorrowDate);

        Map<String, WeatherDTO.ShortTermForecast> tomorrowMap = new TreeMap<>();
        Map<String, WeatherDTO.ShortTermForecast> dayAfterTomorrowMap = new TreeMap<>();

        String targetTime = null;

        for (JsonNode item : items) {
            String fcstDate = item.path("fcstDate").asText();
            String fcstTime = item.path("fcstTime").asText();
            String category = item.path("category").asText();
            String value = item.path("fcstValue").asText();

            if (targetTime == null) {
                targetTime = fcstTime;
                dto.setFcstDate(fcstDate);
                dto.setFcstTime(fcstTime);
            }

            if (fcstDate.equals(dto.getFcstDate()) && fcstTime.equals(targetTime)) {
                switch (category) {
                    case "TMP": dto.setTMP(value); break;
                    case "SKY": dto.setSKY(parseSky(value)); break;
                    case "POP": dto.setPOP(value); break;
                    case "PTY": dto.setPTY(parsePty(value)); break;
                    case "PCP": dto.setPCP(value); break;
                    case "REH": dto.setREH(value); break;
                    case "SNO": dto.setSNO(value); break;
                    case "WSD": dto.setWSD(value); break;
                    case "VEC": dto.setVEC(value); break;
                    case "WAV": dto.setWAV(value); break;
                    case "UUU": dto.setUUU(value); break;
                    case "VVV": dto.setVVV(value); break;
                }
            }
            if (item.path("category").asText().equals("TMX") && fcstDate.equals(baseDate)) dto.setTMX(item.path("fcstValue").asText());
            if (item.path("category").asText().equals("TMN") && fcstDate.equals(baseDate)) dto.setTMN(item.path("fcstValue").asText());

            if (fcstDate.equals(tomorrowDate)) {
                addToMap(tomorrowMap, fcstTime, category, value);
                if ("TMN".equals(category)) dto.setTomorrowTMN(value);
                if ("TMX".equals(category)) dto.setTomorrowTMX(value);
            }
            if (fcstDate.equals(dayAfterTomorrowDate)) {
                addToMap(dayAfterTomorrowMap, fcstTime, category, value);
                if ("TMN".equals(category)) dto.setDayAfterTomorrowTMN(value);
                if ("TMX".equals(category)) dto.setDayAfterTomorrowTMX(value);
            }
        }
        dto.setTomorrowForecasts(sortMap(tomorrowMap));
        dto.setDayAfterTomorrowForecasts(sortMap(dayAfterTomorrowMap));
    }

    // =================  동네예보 맵 추가 헬퍼 메서드 =================
    private void addToMap(Map<String, WeatherDTO.ShortTermForecast> map, String time, String category, String value) {
        map.putIfAbsent(time, new WeatherDTO.ShortTermForecast());
        WeatherDTO.ShortTermForecast forecast = map.get(time);
        forecast.setFcstTime(time);
        switch (category) {
            case "TMP": forecast.setTMP(value); break;
            case "SKY": forecast.setSKY(value); break;
            case "PTY": forecast.setPTY(value); break;
            case "POP": forecast.setPOP(value); break;
            case "REH": forecast.setREH(value); break;
        }
    }

    // =================  동네예보 맵 정렬 헬퍼 메서드 =================
    private ArrayList<WeatherDTO.ShortTermForecast> sortMap(Map<String, WeatherDTO.ShortTermForecast> map) {
        ArrayList<WeatherDTO.ShortTermForecast> list = new ArrayList<>(map.values());
        Collections.sort(list);
        return list;
    }

    // ================= 지진/태풍 시간 포맷팅 헬퍼 메서드 =================
    private String formatEqTime(String rawTime) {
        if (rawTime == null || rawTime.length() < 12) return rawTime;
        return rawTime.substring(4, 6) + "." + rawTime.substring(6, 8) + " " +
                rawTime.substring(8, 10) + ":" + rawTime.substring(10, 12);
    }

    // ================= SKY/PTY 코드 파싱 헬퍼 메서드 =================
    private String parseSky(String value) {
        switch (value) { case "1": return "맑음"; case "3": return "구름많음"; case "4": return "흐림"; default: return value; }
    }
    private String parsePty(String value) {
        switch (value) { case "0": return "강수없음"; case "1": return "비"; case "2": return "비/눈"; case "3": return "눈"; case "4": return "소나기"; default: return value; }
    }
}