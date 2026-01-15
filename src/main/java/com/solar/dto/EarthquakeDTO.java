package com.solar.dto;

import lombok.*;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@ToString
public class EarthquakeDTO {
    private String tmFc;    // 통보 시각
    private String tmEqk;   // 발생 시각
    private String lat;     // 위도
    private String lon;     // 경도
    private String loc;     // 위치
    private String mt;      // 규모
    private String rem;     // 참고사항
    private String img;     // 진앙분포도 이미지 URL (API에서 제공 시)
}