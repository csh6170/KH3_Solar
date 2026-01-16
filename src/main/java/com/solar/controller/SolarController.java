package com.solar.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.solar.service.LocationService;
import com.solar.service.TomorrowWeatherService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets; // ✅ [추가] 한글 깨짐 방지용
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

@Controller
public class SolarController {

    @Autowired
    private TomorrowWeatherService tomorrowWeatherService;

    @Autowired
    private LocationService locationService;

    // 🏠 [1] 버튼만 있는 테스트 페이지 (기존 코드 유지)
    @GetMapping("/test")
    public String mainPage() {
        return "test"; // test.html 반환
    }

    // 🏠 [2] 모달 안에 들어갈 예측 폼 (기존 코드 유지)
    @GetMapping("/predict-form")
    public String predictForm(Model model) {
        model.addAttribute("region1List", locationService.getRegion1List());
        return "index"; // index.html 반환
    }

    // 📍 [API] 시/구/군 목록 반환
    @GetMapping("/api/region2")
    @ResponseBody
    public Set<String> getRegion2(@RequestParam("region1") String region1) {
        return locationService.getRegion2List(region1);
    }

    // 🌤️ [API] 날씨 + 좌표 조회
    @GetMapping("/api/weather")
    @ResponseBody
    public Map<String, Object> getWeather(@RequestParam("region1") String region1,
                                          @RequestParam("region2") String region2) {
        Map<String, Object> response = new HashMap<>();
        LocationService.Point point = locationService.getCoordinate(region1, region2);

        if (point == null) {
            response.put("error", "좌표를 찾을 수 없습니다.");
            return response;
        }

        Map<String, Object> weatherData = tomorrowWeatherService.getTomorrowWeather(point.nx, point.ny);

        if (weatherData != null) {
            response.putAll(weatherData);
            response.put("nx", point.nx);
            response.put("ny", point.ny);
            response.put("lat", point.lat);
            response.put("lon", point.lon);
            response.put("message", "성공");
        } else {
            response.put("error", "기상청 데이터를 가져오지 못했습니다.");
        }
        return response;
    }

    // ⚡ [핵심] Python AI 연동 예측
    @GetMapping("/predict")
    public String predict(@RequestParam double capacity,
                          @RequestParam double temp,
                          @RequestParam double cloud,
                          @RequestParam double radiation,
                          @RequestParam double humidity,
                          @RequestParam String rain,
                          @RequestParam String snow,
                          @RequestParam double wind,
                          @RequestParam double sunshine,
                          @RequestParam double lat,
                          @RequestParam double lon,
                          @RequestParam String region1,
                          @RequestParam String region2,
                          Model model) {

        System.out.println("===== ⚡ AI 발전량 예측 시뮬레이션 =====");

        String projectPath = System.getProperty("user.dir");
        String scriptPath = projectPath + java.io.File.separator + "predict.py";

        // 🔍 [복구 1] 스크립트 경로 확인 로그
        System.out.println("🔍 실행 중인 파이썬 스크립트 경로: " + scriptPath);

        // 🔍 [복구 2] 입력 데이터 확인 로그
        System.out.println(String.format("📍 [위치] %s %s (위도: %.4f, 경도: %.4f)", region1, region2, lat, lon));
        System.out.println(String.format("🌤️ [날씨] 기온: %.1f, 구름: %.1f, 일사량: %.2f, 습도: %.1f", temp, cloud, radiation, humidity));
        System.out.println(String.format("🔌 [설비] 용량: %.1f kW", capacity));

        double rainVal = parseWeatherValue(rain);
        double snowVal = parseWeatherValue(snow);

        double dailyGen = 0.0;
        StringBuilder hourlyHtml = new StringBuilder();

        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "python",
                    scriptPath,
                    String.valueOf(temp),
                    String.valueOf(cloud),
                    String.valueOf(wind),
                    String.valueOf(humidity),
                    String.valueOf(sunshine),
                    String.valueOf(radiation),
                    String.valueOf(snowVal),
                    String.valueOf(rainVal),
                    String.valueOf(lat),
                    String.valueOf(lon)
            );

            Process process = pb.start();

            // ✅ [수정] UTF-8 인코딩 명시 (윈도우에서 한글 로그 깨짐 방지)
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            String line = reader.readLine();

            if (line != null) {
                ObjectMapper mapper = new ObjectMapper();
                JsonNode root = mapper.readTree(line);

                double predictedOneKw = root.path("total").asDouble();
                dailyGen = predictedOneKw * capacity;

                // 🔍 [복구 3] 최종 발전량 로그
                System.out.println("✅ [AI 예측 성공] 1kW당: " + predictedOneKw + " kWh -> 총 발전량: " + dailyGen + " kWh");

                JsonNode hourlyNode = root.path("hourly");
                hourlyHtml.append("<div style='text-align: left; font-size: 0.9rem;'>");

                if (hourlyNode.isArray()) {
                    for (JsonNode node : hourlyNode) {
                        int h = node.path("hour").asInt();
                        double v = node.path("value").asDouble() * capacity;
                        hourlyHtml.append(String.format("<b>%02d시:</b> %.2f kW<br>", h, v));
                    }
                }
                hourlyHtml.append("</div>");
            }

            // ✅ [수정] 에러 로그도 UTF-8로 읽기
            BufferedReader errorReader = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8));
            String errorLine;
            while ((errorLine = errorReader.readLine()) != null) {
                System.err.println("Python Log: " + errorLine);
            }

        } catch (Exception e) {
            e.printStackTrace();
            System.out.println("❌ Python 실행 실패, 기본 계산식으로 대체합니다.");
            dailyGen = capacity * radiation * 0.85;
            hourlyHtml.append("상세 데이터 로드 실패");
        }

        // 결과 가공
        int kwhPrice = 120;
        int savingMoney = (int) (dailyGen * kwhPrice);
        double co2 = dailyGen * 0.424;

        model.addAttribute("gen", String.format("%.2f", dailyGen));
        model.addAttribute("money", String.format("%,d", savingMoney));
        model.addAttribute("co2", String.format("%.2f", co2));
        model.addAttribute("capacity", capacity);
        model.addAttribute("temp", temp);
        model.addAttribute("region1", region1);
        model.addAttribute("region2", region2);
        model.addAttribute("hourlyList", hourlyHtml.toString());

        try { Thread.sleep(800); } catch (InterruptedException e) {}

        return "result";
    }

    private double parseWeatherValue(String val) {
        if (val == null || val.contains("없음") || val.trim().isEmpty()) return 0.0;
        try {
            return Double.parseDouble(val.replaceAll("[^0-9.]", ""));
        } catch (Exception e) { return 0.0; }
    }
}