package com.solar.controller;

import com.solar.dto.WeatherDTO;
import com.solar.service.WeatherService;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

import java.util.ArrayList;
import java.util.List;

@Controller
@RequiredArgsConstructor
public class WeatherController {

    private final WeatherService weatherService;

    // 1. 지역 정보 클래스 확장 (위도/경도 double 타입 추가)
    @Getter
    @AllArgsConstructor
    public static class Region {
        private String name;
        private int nx;
        private int ny;
        private String areaNo; // 생활기상지수용
        private int stnId;     // 기상특보용 지점코드
        private double lat;    // [NEW] 위도 (거리 계산용)
        private double lon;    // [NEW] 경도 (거리 계산용)
    }

    // 2. 주요 도시 매핑 (위경도 좌표 추가)
    private final List<Region> regions = new ArrayList<>(List.of(
            new Region("서울", 60, 127, "1100000000", 109, 37.5636, 126.9626),
            new Region("부산", 98, 76, "2600000000", 159, 35.1796, 129.0756),
            new Region("대구", 89, 90, "2700000000", 143, 35.8714, 128.6014),
            new Region("인천", 55, 124, "2800000000", 109, 37.4563, 126.7052),
            new Region("광주", 58, 74, "2900000000", 156, 35.1595, 126.8526),
            new Region("대전", 67, 100, "3000000000", 133, 36.3504, 127.3845),
            new Region("울산", 102, 84, "3100000000", 159, 35.5384, 129.3114),
            new Region("세종", 66, 103, "3600000000", 133, 36.4800, 127.2890),
            new Region("경기(수원)", 60, 120, "4111000000", 109, 37.2636, 127.0286),
            new Region("강원(강릉)", 92, 131, "4215000000", 105, 37.7519, 128.8760),
            new Region("제주", 52, 38, "5011000000", 184, 33.4996, 126.5312)
    ));

    @GetMapping("/")
    public String weatherPage(Model model,
                              @RequestParam(value = "nx", defaultValue = "60") int nx,
                              @RequestParam(value = "ny", defaultValue = "127") int ny) {

        // 선택된 지역 정보 찾기
        Region currentRegion = regions.stream()
                .filter(r -> r.getNx() == nx && r.getNy() == ny)
                .findFirst()
                .orElse(regions.get(0)); // 기본값 서울

        // 서비스 호출 (좌표 정보 추가 전달)
        WeatherDTO weather = weatherService.getWeather(
                nx, ny,
                currentRegion.getAreaNo(),
                currentRegion.getStnId(),
                currentRegion.getLat(),
                currentRegion.getLon()
        );

        model.addAttribute("weather", weather);
        model.addAttribute("regions", regions);
        model.addAttribute("currentNx", nx);
        model.addAttribute("currentNy", ny);
        model.addAttribute("currentRegionName", currentRegion.getName());

        return "weather";
    }
}