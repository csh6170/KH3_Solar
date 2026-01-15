package com.solar.dto;

import lombok.*;
import java.util.ArrayList;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@ToString
public class WeatherDTO {

    // ================= 기존 메인 데이터 =================
    private String baseDate;
    private String baseTime;
    private String fcstDate;
    private String fcstTime;

    private String TMP;         // 기온
    private String TMN;         // 최저기온
    private String TMX;         // 최고기온
    private String SKY;         // 하늘상태
    private String POP;         // 강수확률
    private String PTY;         // 강수형태
    private String PCP;         // 강수량
    private String SNO;         // 적설량
    private String REH;         // 습도
    private String WSD;         // 풍속
    private String VEC;         // 풍향
    private String UUU;         // 동서바람
    private String VVV;         // 남북바람
    private String WAV;         // 파고

    // ================= [NEW] 꽃가루 농도 위험지수 필드 =================
    // 값이 없을 경우(겨울철 등) null 또는 "0"으로 처리
    private String oakPollenRisk;   // 참나무 (봄)
    private String pinePollenRisk;  // 소나무 (봄)
    private String weedsPollenRisk; // 잡초류 (가을)
    private String pollenComment;   // 통합 코멘트 (가장 높은 등급 기준)

    // ================= AI 체감온도/불쾌지수 분석 데이터 =================
    private String sensibleTemp;    // 체감온도 (AI Regression 예측값)
    private String discomfortIndex; // 불쾌지수 (계산값)
    private String discomfortStage; // 불쾌지수 단계 (낮음/보통/높음/매우높음)
    private String discomfortComment; // 불쾌지수 멘트

    // ================= 초단기예보 (향후 6시간) =================
    private List<ShortTermForecast> shortTermForecasts = new ArrayList<>();

    // =================내일 예보 =================
    private String tomorrowFcstDate;
    private List<ShortTermForecast> tomorrowForecasts = new ArrayList<>();
    private String tomorrowTMN; // 내일 최저
    private String tomorrowTMX; // 내일 최고

    // ================= 모레 예보  =================
    private String dayAfterTomorrowFcstDate;
    private List<ShortTermForecast> dayAfterTomorrowForecasts = new ArrayList<>();
    private String dayAfterTomorrowTMN; // 모레 최저
    private String dayAfterTomorrowTMX; // 모레 최고

    // =================  생활기상지수 필드 =================
    private String uvIndex;         // 자외선 지수 (숫자)
    private String uvStage;         // 자외선 단계 (위험, 높음 등)
    private String uvComment;       // 자외선 코멘트

    private String airIdx;          // 대기정체지수 (숫자)
    private String airStage;        // 대기정체 단계
    private String airComment;      // 대기정체 코멘트

    // ================= [NEW] 미세먼지 정보 필드 (에어코리아) =================
    private String pm10Value;       // 미세먼지 농도
    private String pm10Grade;       // 미세먼지 등급 (1:좋음 ~ 4:나쁨)
    private String pm25Value;       // 초미세먼지 농도
    private String pm25Grade;       // 초미세먼지 등급
    private String khaiGrade;       // 통합대기환경지수
    private String dustComment;     // 미세먼지 코멘트

    // ================= 기상특보 필드 =================
    private String warningMsg; // 특보 내용 (예: "o 폭염주의보 : 서울특별시...")
    private boolean hasWarning; // 특보 발령 여부 (true: 있음, false: 없음)

    // ================= 지진 정보 필드 (미니 카드용) =================
    private boolean hasEarthquake; // 최근(예: 3일 이내) 지진 발생 여부
    private String eqTime;         // 발생 시각
    private String eqLoc;          // 진앙 위치 (예: 경북 경주시 남남서쪽...)
    private String eqMag;          // 규모 (예: 2.5)
    private String eqInt;          // 최대 진도 (optional)
    private String eqDist;         // 사용자와의 거리 (km)
    private String eqSafetyMsg;    // 지진 안전 코멘트

    // ================= 태풍 정보 필드 =================
    private boolean hasTyphoon;     // 태풍 활동 여부
    private String typhoonName;     // 태풍 이름 (예: 제5호 태풍 장미)
    private String typhoonStatus;   // 진행 상태/위치 (예: 부산 남서쪽 약 100km 부근 해상)
    private String typhoonTime;     // 발표 시각
    private String typhoonImg;      // 태풍 경로 이미지 URL (상세 페이지용)
    private String typhoonDist;     // 사용자와의 거리 (km)
    private String typhoonSafetyMsg; // 태풍 안전 코멘트

    // ================= AI 옷차림 추천 필드 =================
    private String clothingRecommendation; // 추천 멘트
    private String outfitIcon;             // 아이콘 클래스명 (예: fas fa-tshirt)

    // ================= AI 기상 캐스터 대본 =================
    private String aiBriefing;

    // ================= AI 배경화면 URL =================
    private String bgImageUrl;

    // ================= AI 날씨 DJ 필드 =================
    private String musicComment;    // DJ 멘트
    private String youtubeVideoId;  // 유튜브 영상 ID


    // ================= 날씨 요약 문구 생성 로직 =================
    public String getWeatherSummary() {
        StringBuilder sb = new StringBuilder();

        // 0. 특보가 있으면 가장 먼저 언급
        if (hasTyphoon) {
            sb.append("🌪️ 현재 태풍 [").append(typhoonName).append("]가 북상 중입니다. 경로를 확인하세요! ");
        } else if (hasWarning && warningMsg != null) {
            sb.append("🚨 현재 [").append(warningMsg.split(":")[0].replace("o", "").trim()).append("]가 발효 중입니다. 안전에 유의하세요! ");
        }

        // 1. 하늘/강수 상태 묘사
        if (PTY != null && !PTY.equals("강수없음") && !PTY.equals("0")) {
            // 비나 눈이 올 때
            switch (PTY) {
                case "비": sb.append("우산을 챙기세요, 비가 내리고 있습니다."); break;
                case "비/눈": sb.append("비와 눈이 섞여 내리는 궂은 날씨입니다."); break;
                case "눈": sb.append("함박눈이 내리고 있습니다. 미끄러움에 주의하세요."); break;
                case "소나기": sb.append("갑작스러운 소나기가 내리고 있습니다."); break;
                case "빗방울": sb.append("빗방울이 조금씩 떨어지고 있습니다."); break;
                default: sb.append("현재 비 또는 눈이 오고 있습니다."); break;
            }
        } else {
            // 강수가 없을 때 (하늘 상태)
            if (SKY != null) {
                switch (SKY) {
                    case "맑음": sb.append("햇살이 가득한 맑고 화창한 날씨입니다."); break;
                    case "구름많음": sb.append("구름이 조금 지나가는 날씨입니다."); break;
                    case "흐림": sb.append("하늘에 구름이 가득해 흐린 날입니다."); break;
                    default: sb.append("현재 날씨는 " + SKY + "입니다."); break;
                }
            } else {
                sb.append("현재 날씨 정보를 불러오고 있습니다.");
            }
        }

        // 2. 기온 정보
        if (TMP != null && !TMP.equals("-")) {
            sb.append(" 현재 기온은 ").append(TMP).append("°C");
        }
        if (TMN != null && !TMN.equals("-") && TMX != null && !TMX.equals("-")) {
            sb.append(" (최저 ").append(TMN).append("° / 최고 ").append(TMX).append("°)");
        }
        sb.append(" 입니다.");

        // 3. 생활지수 경고 추가
        if (uvStage != null && (uvStage.equals("높음") || uvStage.equals("매우높음") || uvStage.equals("위험"))) {
            sb.append(" 자외선이 강하니 차단제를 바르세요.");
        }

        // 미세먼지 요약 추가
        if (pm10Grade != null && (pm10Grade.equals("3") || pm10Grade.equals("4"))) {
            sb.append(" 미세먼지 농도가 높습니다. 마스크를 착용하세요.");
        }

        return sb.toString();
    }

    // [NEW] 등급 숫자 -> 한글 변환 헬퍼 메서드
    public String getGradeText(String grade) {
        if (grade == null) return "-";
        switch (grade) {
            case "1": return "좋음";
            case "2": return "보통";
            case "3": return "나쁨";
            case "4": return "매우나쁨";
            default: return "정보없음";
        }
    }

    // 시간별 예보 정보를 담는 내부 클래스
    @Getter
    @Setter
    @ToString
    public static class ShortTermForecast implements Comparable<ShortTermForecast> {
        private String fcstDate;    // 예보 날짜
        private String fcstTime;    // 예보 시간

        private String TMP;         // 1시간 기온
        private String T1H;         // 1시간 기온 (초단기)

        private String POP;         // 강수확률
        private String SKY;         // 하늘상태
        private String PTY;         // 강수형태
        private String REH;         // 습도
        private String WSD;         // 풍속

        private String RN1;         // 1시간 강수량 (초단기)
        private String PCP;         // 1시간 강수량 (단기)

        private String LGT;         // 낙뢰
        private String UUU;         // 동서바람
        private String VVV;         // 남북바람
        private String VEC;         // 풍향

        public String getTemp() {
            if (TMP != null) return TMP;
            if (T1H != null) return T1H;
            return "-";
        }

        // 시간순 정렬
        @Override
        public int compareTo(ShortTermForecast o) {
            return this.fcstTime.compareTo(o.fcstTime);
        }
    }
}