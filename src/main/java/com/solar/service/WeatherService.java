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
import java.time.format.DateTimeFormatter;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class WeatherService {

    private final ClothingService clothingService;

    // [Optimization] ObjectMapper를 매번 생성하지 않고 재사용
    private final ObjectMapper mapper = new ObjectMapper();

    private final String API_KEY = "eaab499069c4dc1e503f0de460f8fd9add7a1dc08fd28a6b6a2074bd0d2e3162";// 공공데이터포털에서 발급받은 서비스키

    // API URL 목록
    private final String URL_VILAGE = "http://apis.data.go.kr/1360000/VilageFcstInfoService_2.0/getVilageFcst";// 동네예보조회
    private final String URL_ULTRA  = "http://apis.data.go.kr/1360000/VilageFcstInfoService_2.0/getUltraSrtFcst";// 초단기예보조회
    private final String URL_UV     = "http://apis.data.go.kr/1360000/LivingWthrIdxServiceV4/getUVIdxV4";// 자외선지수조회
    private final String URL_WARN   = "http://apis.data.go.kr/1360000/WthrWrnInfoService/getWthrWrnList";// 기상특보조회
    private final String URL_EQK    = "http://apis.data.go.kr/1360000/EqkInfoService/getEqkMsgList";// 지진정보조회
    private final String URL_TYPHOON= "http://apis.data.go.kr/1360000/TyphoonInfoService/getTyphoonInfoList";// 태풍정보조회
    private final String URL_DUST   = "http://apis.data.go.kr/B552584/ArpltnInforInqireSvc/getCtprvnRltmMesureDnsty";// 미세먼지정보조회

    private final String AI_SERVER_URL = "http://localhost:5000";// AI 캐스터 및 DJ 서버 URL

    // =========== 메인 통합 조회 메서드 ===========
    public WeatherDTO getWeather(int nx, int ny, String areaNo, int stnId, double userLat, double userLon) {
        WeatherDTO dto = new WeatherDTO();
        try {
            fetchVilageForecast(dto, nx, ny);
            // [FIX] 최저(TMN) 또는 최고(TMX) 기온이 누락되었다면, 02:00 기준 데이터로 보완 조회
            if (dto.getTMN() == null || dto.getTMX() == null) {
                fetchDailyTempRange(dto, nx, ny); // 보완 로직 호출
            }

            fetchUltraSrtForecast(dto, nx, ny);
            fetchLivingWeather(dto, areaNo);
            fetchFineDust(dto, "서울"); // 기본값 서울, 추후 동적으로 변경 가능
            fetchWeatherWarning(dto, stnId);

            // 사용자 위치 기반 거리 계산 및 안전 분석 포함
            fetchEarthquake(dto, userLat, userLon);
            fetchTyphoon(dto, userLat, userLon);

            String recommendation = clothingService.recommendOutfit(dto.getTMP(), dto.getPTY(), dto.getWSD());
            String icon = clothingService.getOutfitIcon(dto.getTMP());
            dto.setClothingRecommendation(recommendation);
            dto.setOutfitIcon(icon);

            fetchAiBriefing(dto);
            fetchAiDj(dto);
            selectBgImage(dto);

        } catch (Exception e) {
            log.error("날씨 통합 조회 실패", e);
        }
        return dto;
    }

    // ================= 일일 최저/최고 기온 보완 로직 =================
    private void fetchDailyTempRange(WeatherDTO dto, int nx, int ny) {
        try {
            // 오늘 날짜의 02:00 데이터 요청 (이때는 항상 최저/최고 기온이 포함됨)
            String baseDate = LocalDateTime.now(ZoneId.of("Asia/Seoul")).format(DateTimeFormatter.ofPattern("yyyyMMdd"));
            String baseTime = "0200";

            URI uri = buildUri(URL_VILAGE, baseDate, baseTime, nx, ny);
            String json = new RestTemplate().getForObject(uri, String.class);
            JsonNode root = mapper.readTree(json);

            // 만약 정상 응답이 아니라면 종료
            if (!"00".equals(root.path("response").path("header").path("resultCode").asText())) return;

            JsonNode items = root.path("response").path("body").path("items").path("item");
            for (JsonNode item : items) {
                String category = item.path("category").asText();
                String fcstDate = item.path("fcstDate").asText();
                String value = item.path("fcstValue").asText();

                // 오늘 날짜에 해당하는 값만 추출
                if (fcstDate.equals(baseDate)) {
                    // 비어있는 값만 채워넣기 (이미 있으면 건드리지 않음)
                    if ("TMN".equals(category) && dto.getTMN() == null) {
                        dto.setTMN(value);
                    }
                    if ("TMX".equals(category) && dto.getTMX() == null) {
                        dto.setTMX(value);
                    }
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
            String fromDate = now.minusDays(7).format(DateTimeFormatter.ofPattern("yyyyMMdd")); // 최근 7일

            URI uri = UriComponentsBuilder.fromUriString(URL_EQK)
                    .queryParam("serviceKey", API_KEY)
                    .queryParam("pageNo", "1")
                    .queryParam("numOfRows", "1") // 가장 최근 1건
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

            // 거리 계산 로직
            try {
                double eqLat = Double.parseDouble(item.path("lat").asText("0"));
                double eqLon = Double.parseDouble(item.path("lon").asText("0"));

                if (eqLat != 0 && eqLon != 0) {
                    double dist = calculateDistance(userLat, userLon, eqLat, eqLon);
                    dto.setEqDist(String.format("%.1fkm", dist));

                    // [AI Logic] 거리와 규모 기반 안전 코멘트 생성
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

    // ========== [AI Logic] 지진 안전도 분석기 ===========
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

            // 거리 계산 및 안전 분석
            try {
                double typLat = Double.parseDouble(item.path("lat").asText("0"));
                double typLon = Double.parseDouble(item.path("lon").asText("0"));
                String speedStr = item.path("typWs").asText("0").replaceAll("[^0-9.]", ""); // "24m/s" -> "24"
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

    // =========== [AI Logic] 태풍 안전도 분석기 ===========
    private String analyzeTyphoonSafety(double windSpeed, double distanceKm) {
        if (distanceKm > 800) return "아직 거리가 멉니다. 태풍 정보를 주시하세요.";

        if (distanceKm < 300) {
            if (windSpeed > 30) return "🚨 태풍의 직접 영향권입니다! 외출을 자제하세요.";
            else return "태풍이 접근 중입니다. 비바람에 주의하세요.";
        } else {
            return "태풍의 간접 영향이 있을 수 있습니다. 우산을 챙기세요.";
        }
    }

    // =========== [Utility] 하버사인 공식 (두 좌표 사이의 거리 계산, 단위: km) ===========
    private double calculateDistance(double lat1, double lon1, double lat2, double lon2) {
        final int R = 6371; // 지구의 반지름 (km)
        double latDistance = Math.toRadians(lat2 - lat1);
        double lonDistance = Math.toRadians(lon2 - lon1);
        double a = Math.sin(latDistance / 2) * Math.sin(latDistance / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(lonDistance / 2) * Math.sin(lonDistance / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return R * c;
    }

    // ================= 기존 로직들 (ObjectMapper 재사용 적용) =================

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

    // 상세 리스트 조회용 (기존 유지)
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
                dto.setLat(item.path("lat").asText("0")); // [FIX] lat 키 값 수정 (API마다 다를 수 있음, 보통 typhoonInfo는 lat/lon 제공)
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

    private ArrayList<WeatherDTO.ShortTermForecast> sortMap(Map<String, WeatherDTO.ShortTermForecast> map) {
        ArrayList<WeatherDTO.ShortTermForecast> list = new ArrayList<>(map.values());
        Collections.sort(list);
        return list;
    }

    private String formatEqTime(String rawTime) {
        if (rawTime == null || rawTime.length() < 12) return rawTime;
        return rawTime.substring(4, 6) + "." + rawTime.substring(6, 8) + " " +
                rawTime.substring(8, 10) + ":" + rawTime.substring(10, 12);
    }

    private String parseSky(String value) {
        switch (value) { case "1": return "맑음"; case "3": return "구름많음"; case "4": return "흐림"; default: return value; }
    }
    private String parsePty(String value) {
        switch (value) { case "0": return "강수없음"; case "1": return "비"; case "2": return "비/눈"; case "3": return "눈"; case "4": return "소나기"; default: return value; }
    }
}