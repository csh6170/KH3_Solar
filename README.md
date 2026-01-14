# ☀️ Solar AI Prediction Project
> **기상청 단기예보 Open API와 AI 모델을 결합한 태양광 발전량 예측 및 수익 분석 서비스**

본 프로젝트는 실시간 기상 데이터와 머신러닝 모델을 활용하여 특정 지역의 내일 태양광 발전량을 예측하고, 이를 바탕으로 가구별 전기요금 절약액을 산출해주는 웹 서비스입니다.

---

## 🚀 주요 업데이트 및 기술적 의사결정 (2026-01-14)

### 🎨 1. UI/UX 리뉴얼 (Front-end)
* **프레임워크 전환**: Bootstrap 5의 의존성을 제거하고 **Tailwind CSS**를 도입하여 라이브러리 경량화 및 디자인 커스텀 자유도를 확보하였습니다.
* **모달 기반 인터페이스**: 메인 페이지와 예측 폼을 분리하여 모달(Modal) 형식으로 구현, 사용자 경험(UX)의 연속성을 강화하였습니다.
* **인터랙티브 컴포넌트**: 
    * **Tippy.js**: 시간대별 상세 예측치를 부드러운 애니메이션 팝오버로 제공합니다.
    * **SweetAlert2**: 데이터 조회 중 로딩 상태 및 유효성 검사 알림을 미려하게 구현하였습니다.
    * **Font Awesome 6**: 시각적 직관성을 위한 아이콘 시스템을 구축하였습니다.

### ⚙️ 2. 데이터 엔진 및 API 최적화 (Back-end)
* **기상청 API 연동 고도화**: 
    * 데이터 타입을 `Map<String, Object>`로 확장하여 강수확률(POP), 강수량(PCP) 등 다양한 데이터 형식을 수용하도록 설계하였습니다.
    * 태양광 발전에 유효한 시간대(**06:00 ~ 20:00**) 데이터만 필터링하여 예측 모델의 입력 데이터 정확도를 높였습니다.
* **API 429(Too Many Requests) 장애 해결**: 
    * **문제**: 모달 오픈 시 iframe 전체 새로고침으로 인한 불필요한 API 중복 호출 발생.
    * **해결**: `window.resetAll()` 자바스크립트 함수를 구현하여, 페이지 새로고침 없이 DOM 요소의 입력값만 초기화함으로써 API 호출 횟수를 최소화하고 트래픽 초과 문제를 해결하였습니다.

### 🧠 3. 사용자 중심 날씨 해석 로직
사용자가 복잡한 기상 수치를 해석할 필요 없이, 로직을 통해 직관적인 메시지를 출력합니다.
| 조건 (Condition) | 출력 메시지 (Display) | 비고 |
|:--- |:--- |:--- |
| **강수확률(POP) ≥ 90%** | 비 ☔ 또는 눈 🌨️ | 확실한 기상 변화 알림 |
| **0% < 강수확률 < 90%** | 강수확률 XX% ☂️ | 불확실성에 대비하도록 확률 표기 |
| **강수확률 = 0%** | 맑음 / 흐림 등 | 구름 데이터(SKY) 기반 자동 분류 |

---

## 🛠 Tech Stack

### Language & Framework
* **Back-end**: Java 17, Spring Boot 3.x
* **Front-end**: Thymeleaf, Tailwind CSS, JavaScript (Vanilla ES6)
* **AI/Analysis**: Python 3.x, Pandas, Scikit-learn (Random Forest)

### Libraries & API
* **Weather Data**: 기상청 단기예보 조회서비스 (Open API)
* **Solar Data**: 한국동서발전 일자별 발전량 데이터 (공공데이터포털)
* **UI Components**: Font Awesome 6, Tippy.js, SweetAlert2

---

## 📂 프로젝트 구조 (핵심 파일)
* `predict.py`: Java로부터 날씨 데이터를 수신하여 AI 모델(`.pkl`)을 통해 실시간 예측을 수행하는 엔진.
* `SolarController.java`: API 응답 구조를 유연하게 관리하고 뷰(View)와 통신하는 컨트롤러.
* `main.html`: 모달 제어 로직 및 iframe 통신을 담당하는 부모 페이지.
* `index.html`: Tailwind 기반의 사용자 입력 폼 및 실시간 날씨 상태 출력 페이지.

---

## 💡 성능 평가
* **AI 모델**: Random Forest Regressor 기반 학습
* **정확도(R2 Score)**: 0.8+ (검증 데이터셋 기준)
* **특이사항**: Bell Curve 로직을 적용하여 시간대별 태양 고도에 따른 발전 효율 보정 처리

---
