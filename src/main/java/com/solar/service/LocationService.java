package com.solar.service; // 패키지명 확인

import jakarta.annotation.PostConstruct;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Service
public class LocationService {

    private Map<String, Set<String>> regionHierarchy = new TreeMap<>();
    private Map<String, Point> coordinateMap = new HashMap<>();

    public static class Point {
        public final int nx;
        public final int ny;
        public final double lat;
        public final double lon;

        public Point(int nx, int ny, double lat, double lon) {
            this.nx = nx;
            this.ny = ny;
            this.lat = lat;
            this.lon = lon;
        }
    }

    @PostConstruct
    public void loadCsv() {
        try {
            ClassPathResource resource = new ClassPathResource("weather_location.csv");
            // 파이썬에서 utf-8-sig로 저장했으므로 UTF_8로 읽으면 됩니다.
            BufferedReader br = new BufferedReader(new InputStreamReader(resource.getInputStream(), StandardCharsets.UTF_8));

            String line;
            boolean isHeader = true; // 첫 줄(제목) 건너뛰기용

            while ((line = br.readLine()) != null) {
                if (isHeader) { isHeader = false; continue; }

                // csv 형식: Region1,Region2,Region3,nx,ny
                String[] data = line.split(",", -1); // 빈 값도 포함해서 자르기

                if (data.length < 7) continue;

                String region1 = data[0].trim(); // 시/도
                String region2 = data[1].trim(); // 시/구/군
                if (region1.contains("이어도") || region2.contains("이어도")) {
                    continue;
                }
                String region3 = data[2].trim(); // 읍/면/동

                // 좌표 파싱
                try {
                    int nx = Integer.parseInt(data[3].trim());
                    int ny = Integer.parseInt(data[4].trim());
                    double lat = Double.parseDouble(data[5].trim()); // 위도
                    double lon = Double.parseDouble(data[6].trim()); // 경도

                    // 1. 시/도 목록 만들기
                    if (!region1.isEmpty()) {
                        regionHierarchy.putIfAbsent(region1, new TreeSet<>());

                        // 2. 시/구/군 목록 만들기 (비어있지 않은 경우만)
                        if (!region2.isEmpty()) {
                            regionHierarchy.get(region1).add(region2);

                            // 3. 좌표 매핑 (우리는 '시/도 + 시/구/군' 까지만 검색 키로 사용)
                            // 주의: 읍/면/동(region3)이 없는 행을 우선적으로 좌표로 등록하거나,
                            // 그냥 덮어씌워도 대략적인 위치는 맞습니다.
                            coordinateMap.put(region1 + " " + region2, new Point(nx, ny, lat, lon));
                        }
                    }
                } catch (NumberFormatException e) {
                    continue; // 숫자가 아닌 행은 무시
                }
            }
            System.out.println("✅ 전국 기상청 좌표 로딩 완료! (총 " + coordinateMap.size() + "개 2단계 지역)");

        } catch (Exception e) {
            e.printStackTrace();
            System.err.println("❌ weather_location.csv 파일 로딩 실패");
        }
    }

    public Set<String> getRegion1List() {
        return regionHierarchy.keySet();
    }

    public Set<String> getRegion2List(String region1) {
        return regionHierarchy.getOrDefault(region1, Collections.emptySet());
    }

    public Point getCoordinate(String region1, String region2) {
        return coordinateMap.get(region1 + " " + region2);
    }
}