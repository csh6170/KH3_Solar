---

```markdown
# ☀️ Solar AI & Weather Safety Dashboard
> **기상청 API와 AI 모델을 결합한 지능형 태양광 발전 예측 및 재난 안전 큐레이션 서비스**

![Main Dashboard Screen](./images/main_dashboard.png)
*(▲ 메인 대시보드 스크린샷을 여기에 넣어주세요)*

## 📖 프로젝트 개요 (Project Overview)
본 프로젝트는 **실시간 기상 데이터**를 기반으로 **태양광 발전량**을 예측하여 경제적 가치를 분석하고, 사용자의 일상과 안전을 지키기 위한 **AI 기반 라이프스타일(옷차림, 음악) 및 재난(지진, 태풍) 정보**를 통합 제공하는 웹 서비스입니다.

단순한 날씨 정보 제공을 넘어, **"내일 내 태양광 패널이 얼마나 벌어다 줄까?"** 라는 질문에 답하고, **"오늘 지진이나 태풍 위험은 없을까?"** 라는 불안을 해소하는 것을 목표로 합니다.

---

## 🚀 주요 기능 (Key Features)

### 1. ⚡ 태양광 발전 예측 및 수익 분석 (Solar Prediction)
* **정밀 지역 및 용량 설정**: 전국 시/도, 시/구/군 단위의 세부 지역 선택과 설비 용량(kW) 입력을 지원합니다.
* **AI 발전량 예측**: 과거 발전 데이터와 기상관측(ASOS) 데이터를 학습한 **Random Forest 모델**이 내일의 시간대별(06시~19시) 예상 발전량(kWh)을 산출합니다.
* **경제성 분석 리포트**: 
    * 예상 총 발전량 및 시간대별 발전 추이 그래프 제공
    * 누진세를 고려한 **예상 전기요금 절약액(KRW)** 자동 계산
    * *예시: 서울 강남구 3kW 설비 → 예상 발전량 16.91kWh, 예상 절약액 2,029원*

### 2. 🎙️ AI 라이프스타일 큐레이션 (AI Caster & Curator)
* **AI Caster**: "구름이 잔뜩 꼈어요. 우산 챙기세요!"와 같이 실시간 날씨를 분석해 친근한 어조로 브리핑합니다.
* **AI Outfit Suggestion**: 기온과 습도 데이터를 기반으로 최적의 옷차림(예: 울 코트, 히트텍 등)을 추천합니다.
* **AI Weather DJ**: 날씨 분위기(흐림, 맑음, 비 등)에 어울리는 음악 장르와 코멘트를 제공합니다.

### 3. 🚨 실시간 재난 안전 모니터링 (Safety Dashboard)
* **지진 감시 (Earthquake Watch)**: 
    * 최근 30일 이내 발생한 지진 기록 및 규모별 통계 시각화
    * 실시간 대응 단계(관심/주의) 및 상황별 행동 요령 안내
* **태풍 감시 (Typhoon Watch)**:
    * 실시간 태풍 활동 여부, 경로, 최대 풍속 정보 제공
    * 태풍 강도에 따른 위험도 설명 및 대처법 가이드
* **통합 안전 브리핑**: 미세먼지(PM10), 자외선 지수, 재난 상태를 종합하여 '양호/주의/위험' 상태를 진단합니다.

---

## 🔄 서비스 흐름 (Service Flow)

### Step 1. 메인 대시보드 & 날씨 확인
사용자는 메인 화면에서 전국 주요 도시의 실시간 날씨 상태를 확인하고, AI 캐스터의 브리핑과 추천 음악/옷차림 정보를 제공받습니다.

### Step 2. 태양광 발전 예측 (Simulation)
1.  **[태양광 발전 예측]** 버튼 클릭
2.  지역(시/군/구) 선택 및 설비 용량(kW) 입력
3.  **[내일 발전량 예측하기]** 클릭 시, 실시간 기상청 예보 데이터 호출
4.  Python AI 엔진이 발전량을 예측하여 **결과 리포트 모달** 출력

### Step 3. 재난 정보 조회 (Monitoring)
하단의 **[지난 지진 기록]** 또는 **[지난 태풍 기록]** 버튼을 클릭하여 별도의 대시보드 페이지로 이동, 과거 30일간의 상세 데이터와 실시간 위험 정보를 확인합니다.

---

## 🛠 기술 스택 (Tech Stack)

| Category | Technology |
| :--- | :--- |
| **Language** | Java 17, Python 3.9, JavaScript (ES6) |
| **Framework** | Spring Boot 3.x, Thymeleaf |
| **CSS / UI** | **Tailwind CSS**, Font Awesome 6 |
| **Libraries** | Tippy.js (Tooltip), SweetAlert2 (Modal/Alert), Chart.js |
| **AI / Data** | Scikit-learn (Random Forest), Pandas, NumPy |
| **Open API** | 기상청(단기예보, 지진, 태풍), 에어코리아(대기질), 공공데이터포털 |
| **IDE / Tool** | IntelliJ IDEA, VS Code, Git |

---

## ⚙️ 기술적 고도화 및 문제 해결 (Technical Highlights)

### 1. API 트래픽 최적화 및 모달 제어
* **문제**: 모달 창에서 예측 수행 후 닫을 때마다 전체 페이지가 새로고침되어 기상청 API 호출 횟수가 급증하는 문제 발생 (429 Too Many Requests 위험).
* **해결**: `window.resetAll()` 자바스크립트 함수를 구현하여, 페이지 리로드 없이 DOM 요소(입력 필드, 결과창)만 초기화되도록 로직을 개선하여 API 트래픽을 효율적으로 관리함.

### 2. 데이터 정제 및 모델 정확도 향상
* **로직**: 기상청 단기예보 데이터는 24시간 제공되지만, 태양광 발전은 일조 시간에만 가능함.
* **해결**: Python 전처리 과정에서 유효 발전 시간대인 **06:00 ~ 20:00** 데이터만 필터링하여 모델에 주입. 또한 태양 고도에 따른 발전 효율 변화를 반영하기 위해 **Bell Curve(정규분포) 가중치** 로직을 적용.

### 3. UI/UX 현대화 (Bootstrap → Tailwind CSS)
* 기존 Bootstrap의 정형화된 디자인에서 벗어나 **Tailwind CSS**를 도입하여 커스텀 디자인 자유도를 높이고, 직관적인 대시보드 레이아웃을 구현함.

---

## 📂 프로젝트 구조 (Project Structure)

```bash
├── src
│   ├── main
│   │   ├── java/com/solar/project
│   │   │   ├── controller
│   │   │   │   └── SolarController.java  # 메인 컨트롤러 (API 통신 및 뷰 매핑)
│   │   │   ├── service
│   │   │   │   └── WeatherService.java   # 기상청/재난 API 데이터 파싱 로직
│   │   │   └── dto                       # 데이터 전송 객체
│   │   └── resources
│   │       ├── static                    # CSS, JS, Images
│   │       └── templates
│   │           ├── index.html            # 메인 대시보드
│   │           ├── main.html             # 모달 및 iframe 부모 페이지
│   │           ├── earthquake.html       # 지진 감시 대시보드
│   │           └── typhoon.html          # 태풍 감시 대시보드
├── python
│   ├── predict.py                        # AI 예측 엔진 (Java와 ProcessBuilder로 통신)
│   └── solar_model.pkl                   # 학습된 Random Forest 모델
└── README.md

```

---

## 📊 성능 평가 (Performance)

* **Model**: Random Forest Regressor
* **Training Data**: 한국동서발전 시간대별 태양광 발전량 (2021-2024) + 종관기상관측(ASOS) 날씨 데이터
* **Accuracy**: R² Score 0.8+ (검증 셋 기준)

---

## 👨‍💻 팀원 및 역할 (Team Members)

* **[본인 이름]**: 풀스택 개발 (Spring Boot, Tailwind CSS), AI 모델 연동, 재난 API 통합 구현
* *(다른 팀원 정보가 있다면 추가)*

---

## © License

This project is licensed under the MIT License.
Data Sources: 기상청(KMA), 한국동서발전(EWP), 공공데이터포털

```

```
