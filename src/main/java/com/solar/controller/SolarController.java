package com.solar.controller;

import com.fasterxml.jackson.databind.JsonNode;     // ✅ [추가] JSON 처리용
import com.fasterxml.jackson.databind.ObjectMapper; // ✅ [추가] JSON 처리용
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
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

@Controller
public class SolarController {

    @Autowired
    private TomorrowWeatherService tomorrowWeatherService;

    @Autowired
    private LocationService locationService;

    // 🏠 메인 페이지
    // 1. 버튼만 있는 메인 페이지
    @GetMapping("/test")
    public String mainPage() {
        return "test"; // 버튼만 있는 html 파일명 (예: main.html)
    }

    // 2. 모달 안에 들어갈 예측 폼 (기존 코드)
    @GetMapping("/predict-form")
    public String predictForm(Model model) {
        model.addAttribute("region1List", locationService.getRegion1List());
        return "index"; // 제공해주신 태양광 폼 html 파일명 (index.html)
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

        // ▼▼▼ [수정] 반환 타입을 Map<String, Double> -> Map<String, Object>로 변경 ▼▼▼
        // (POP, PTY 등 다양한 데이터를 담기 위함)
        Map<String, Object> weatherData = tomorrowWeatherService.getTomorrowWeather(point.nx, point.ny);

        if (weatherData != null) {
            response.putAll(weatherData); // pop, temp, rain 등이 여기서 다 들어감
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

    // ⚡ [핵심] Python AI 연동 예측 (시간대별 데이터 기능 추가)
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
                          Model model) {

        System.out.println("===== ⚡ AI 발전량 예측 시뮬레이션 =====");

        // 1. 현재 실행 경로(프로젝트 루트)를 가져와서 파일 구분자(\ 또는 /)와 함께 연결합니다.
        String projectPath = System.getProperty("user.dir");
        String scriptPath = projectPath + java.io.File.separator + "predict.py";

        // [확인용] 실제 어떤 경로로 실행되는지 콘솔에 출력해줍니다.
        System.out.println("🔍 실행 중인 파이썬 스크립트 경로: " + scriptPath);

        double rainVal = parseWeatherValue(rain);
        double snowVal = parseWeatherValue(snow);

        double dailyGen = 0.0;
        StringBuilder hourlyHtml = new StringBuilder(); // ✅ [추가] 팝오버용 HTML 저장소

        try {
            // 1. Python 스크립트 실행
            ProcessBuilder pb = new ProcessBuilder(
                    "python",
                    scriptPath, // ✅ 절대 경로 유지
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
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            String line = reader.readLine(); // Python의 JSON 출력 읽기 (한 줄로 옴)

            if (line != null) {
                // 2. JSON 파싱 시작
                ObjectMapper mapper = new ObjectMapper();
                JsonNode root = mapper.readTree(line);

                // 3. 총 발전량 추출
                double predictedOneKw = root.path("total").asDouble();
                dailyGen = predictedOneKw * capacity;

                // 4. 시간대별 데이터 추출 및 HTML 만들기
                JsonNode hourlyNode = root.path("hourly");
                hourlyHtml.append("<div style='text-align: left; font-size: 0.9rem;'>"); // 스타일 시작

                if (hourlyNode.isArray()) {
                    for (JsonNode node : hourlyNode) {
                        int h = node.path("hour").asInt();
                        double v = node.path("value").asDouble() * capacity; // 용량 곱하기

                        // 예: "<b>06시:</b> 0.52 kW<br>" 형식으로 추가
                        hourlyHtml.append(String.format(
                                "<b>%02d시:</b> %.2f kW<br>", h, v
                        ));
                    }
                }
                hourlyHtml.append("</div>"); // 스타일 끝
            }

            System.out.println("🐍 Python AI 예측 결과(Total): " + dailyGen + " kWh");

            // 에러 로그 읽기
            BufferedReader errorReader = new BufferedReader(new InputStreamReader(process.getErrorStream()));
            String errorLine;
            while ((errorLine = errorReader.readLine()) != null) {
                System.err.println("Python Error: " + errorLine);
            }

        } catch (Exception e) {
            e.printStackTrace();
            System.out.println("❌ Python 실행 실패, 기본 계산식으로 대체합니다.");
            dailyGen = capacity * radiation * 0.85;
            hourlyHtml.append("상세 데이터를 불러오지 못했습니다."); // 에러 시 팝오버 메시지
        }

        // 5. 결과 가공
        int kwhPrice = 120;
        int savingMoney = (int) (dailyGen * kwhPrice);
        double co2 = dailyGen * 0.424;

        // 6. 화면 전달
        model.addAttribute("gen", String.format("%.2f", dailyGen));
        model.addAttribute("money", String.format("%,d", savingMoney));
        model.addAttribute("co2", String.format("%.2f", co2));
        model.addAttribute("capacity", capacity);
        model.addAttribute("temp", temp);

        // ✅ [추가] 시간대별 HTML 문자열을 화면으로 보냄
        model.addAttribute("hourlyList", hourlyHtml.toString());

        // 7. [UX] 1초 딜레이 (주석 해제 추천)
        try { Thread.sleep(1000); } catch (InterruptedException e) {}

        return "result";
    }

    private double parseWeatherValue(String val) {
        if (val == null || val.contains("없음") || val.trim().isEmpty()) {
            return 0.0;
        }
        try {
            return Double.parseDouble(val.replaceAll("[^0-9.]", ""));
        } catch (Exception e) {
            return 0.0;
        }
    }
}