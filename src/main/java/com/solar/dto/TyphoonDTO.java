package com.solar.dto;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@ToString
public class TyphoonDTO {
    private String tmFc;        // 발표 시각
    private String typSeq;      // 태풍 번호 (예: 5)
    private String typName;     // 태풍 이름 (예: 장미)
    private String typEn;       // 태풍 영문 이름
    private String manFc;       // 발표 관서 (예: 기상청)
    private String loc;         // 현재 위치 설명 (예: 제주 서귀포 남쪽 약 300km 부근 해상)
    private String lat;         // 위도
    private String lon;         // 경도
    private String dir;         // 진행 방향
    private String sp;          // 진행 속도 (km/h)
    private String ps;          // 중심 기압 (hPa)
    private String ws;          // 최대 풍속 (m/s)
    private String er;          // 강풍 반경 (km)
}