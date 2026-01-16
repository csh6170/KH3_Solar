import sys  # [필수 추가] 시스템 설정 제어
import io   # [필수 추가] 입출력 인코딩 제어
from fastapi import FastAPI
from pydantic import BaseModel
import pandas as pd
from sklearn.tree import DecisionTreeClassifier
from sklearn.linear_model import LinearRegression
import uvicorn
import random
import numpy as np
from contextlib import asynccontextmanager # lifespan을 위한 모듈

# --- [추가] 터미널 인코딩 에러 방지 설정 (CP949 환경 대응) ---
if sys.stdout.encoding != 'utf-8':
    sys.stdout = io.TextIOWrapper(sys.stdout.detach(), encoding='utf-8')
    sys.stderr = io.TextIOWrapper(sys.stderr.detach(), encoding='utf-8')

# lifespan 정의: 앱 시작/종료 시 실행될 로직
@asynccontextmanager
async def lifespan(app: FastAPI):
    # 앱 시작 시 실행
    print("[파이썬 AI 서버 실행!] AI 서버를 \"시작\"합니다...............!!!!")
    yield
    # 앱 종료 시 실행 (Ctrl+C 등)
    print("[파이썬 AI 서버 종료!] AI 서버를 \"종료\"합니다...............!!!!")

# FastAPI 앱 생성 시 lifespan 파라미터 주입 필수!
app = FastAPI(lifespan=lifespan)
# ================= 1. 옷차림 추천 모델 (분류 - Classification) =================
# 학습 데이터 준비 (실제로는 CSV 파일 등에서 로드하지만, 학습용으로 직접 생성)
# 0:맑음(강수없음), 1:비/눈
# Label: 추천 옷차림
data_cloth = {
    'temp': [30, 28, 25, 24, 20, 18, 15, 12, 10, 5, 0, -5,
             30, 25, 10, 0], # 기온
    'rain': [0,  0,  0,  0,  0,  0,  0,  0,  0, 0, 0,  0,
             1,  1,  1,  1], # 강수여부 (0:안옴, 1:옴)
    'label': [
        "햇볕이 뜨거워요. 시원한 민소매나 린넨 소재를 추천해요.",
        "더운 날씨엔 가벼운 반팔과 반바지가 좋아요.",
        "활동하기 딱 좋은 날, 반팔에 얇은 셔츠를 걸쳐보세요.",
        "가볍게 입기 좋은 반팔과 면바지 조합이에요.",
        "긴팔 티셔츠에 청바지로 캐주얼하게.",
        "편안한 맨투맨에 가디건 하나 챙기세요.",
        "공기가 쌀쌀해요. 포근한 니트와 자켓이 필요해요.",
        "찬 바람을 막아줄 트렌치코트나 야상이 좋겠어요.",
        "트렌치코트에 도톰한 바지로 보온을 챙기세요.",
        "추운 날엔 울 코트 속에 히트텍을 꼭 챙겨 입으세요.",
        "너무 추워요! 따뜻한 패딩과 목도리로 무장하세요.",
        "살을 에는 혹한기엔 롱패딩과 방한용품이 필수예요.",
        "비 오고 후덥지근할 땐 반팔에 레인부츠가 딱이죠.",
        "비 오는 날엔 활동성 좋은 반팔과 우비를 추천해요.",
        "쌀쌀한 빗길엔 트렌치코트와 우산을 챙기세요.",
        "눈 오는 날, 패딩과 미끄럼 방지 신발로 안전하게."
    ]
}

# 3. 모델 학습 (서버 시작 시 1회 실행)
df_cloth = pd.DataFrame(data_cloth)
X_cloth = df_cloth[['temp', 'rain']]
y_cloth = df_cloth['label']

model_cloth = DecisionTreeClassifier()
model_cloth.fit(X_cloth, y_cloth)

# ================= 2. 체감온도 예측 모델 (회귀 - Regression) =================
# 설명: 기온(Temp), 습도(Hum), 풍속(Wind)을 입력받아 '체감온도(Sensible Temp)'를 예측
# 선형 회귀 학습을 위해 가상의 데이터셋을 생성합니다. (실제 공식을 근사하게 학습)

# 학습 데이터 생성 함수 (공식 기반 시뮬레이션 데이터)
def generate_sensible_temp_data(n_samples=1000):
    temps = np.random.uniform(-20, 35, n_samples) # -20도 ~ 35도
    hums = np.random.uniform(0, 100, n_samples)   # 습도 0~100%
    winds = np.random.uniform(0, 20, n_samples)   # 풍속 0~20m/s
    targets = []

    for t, h, w in zip(temps, hums, winds):
        # 여름철 (기온 높음): 습도가 높으면 더 덥게 느껴짐 (Heat Index 유사 로직)
        if t >= 20:
            sensible = t + (h / 100) * 0.1 * t # 습도가 높으면 체감온도 상승
        # 겨울철 (기온 낮음): 바람이 불면 더 춥게 느껴짐 (Wind Chill 유사 로직)
        else:
            sensible = t - (w * 0.7) # 바람이 불면 체감온도 하강
        
        targets.append(sensible)
    
    return pd.DataFrame({'temp': temps, 'hum': hums, 'wind': winds, 'target': targets})

# 데이터 생성 및 학습
df_sensible = generate_sensible_temp_data()
X_sensible = df_sensible[['temp', 'hum', 'wind']]
y_sensible = df_sensible['target']

model_sensible = LinearRegression() # 선형 회귀 모델
model_sensible.fit(X_sensible, y_sensible)

print("AI 모델 2종 학습 완료! (옷차림:DT, 체감온도:LinearRegression)")



# ================= API 엔드포인트 정의 =================

# 1. 옷차림 예측 요청 DTO
class WeatherRequest(BaseModel):
    temp: float
    pty: str  # 기상청 코드 ("0", "1", "비" 등)

@app.post("/predict")
def predict_outfit(req: WeatherRequest):
    # 데이터 전처리: 기상청 PTY 코드를 0(맑음) 또는 1(비/눈)로 변환
    rain_status = 0
    if req.pty and req.pty != "0" and req.pty != "강수없음":
        rain_status = 1

    # 예측 수행 / 리스트 대신 DataFrame으로 변환하여 예측 (Feature Name 경고 해결)
    input_df = pd.DataFrame([[req.temp, rain_status]], columns=['temp', 'rain'])
    
    prediction = model_cloth.predict(input_df)
    result_text = prediction[0]

    # 파이썬 서버임을 티내기 위해 접두어 추가
    return {"recommendation": f"📌 {result_text}"}


# 2. 체감온도 예측 요청 DTO
class SensibleRequest(BaseModel):
    temp: float
    hum: float
    wind: float

@app.post("/sensible")
def predict_sensible_temp(req: SensibleRequest):
    input_df = pd.DataFrame([[req.temp, req.hum, req.wind]], columns=['temp', 'hum', 'wind'])
    
    # 입력된 데이터로 체감온도 예측
    # 입력값: [[기온, 습도, 풍속]]
    predicted_value = model_sensible.predict(input_df)
    
    # 소수점 1자리까지 반올림
    result = round(predicted_value[0], 1)
    
    return {"sensible_temp": result}




# ================= AI 기상 캐스터 (브리핑 생성) =================

class BriefingRequest(BaseModel):
    temp: str       # 기온
    sky: str        # 하늘상태
    pty: str        # 강수형태
    pop: str        # 강수확률
    pm10: str = "좋음" # 미세먼지 (기본값)

@app.post("/briefing")
def generate_briefing(req: BriefingRequest):
    
    # 기본 인사말
    intro = random.choice([
        "🎤 안녕하세요! AI 기상캐스터입니다.",
        "🌈 반갑습니다. 오늘의 날씨를 전해드릴게요!",
        "📢 오늘 하루도 힘차게 시작해볼까요? 날씨 브리핑입니다."
    ])

    # 날씨 상태 묘사
    status_desc = ""
    if req.pty not in ["0", "강수없음", "정보 없음"]:
        status_desc = random.choice([
            f"☔ 현재 밖에는 {req.pty}가 내리고 있어요. 우산 꼭 챙기셔야겠어요!",
            f"🌧️ 아이고, {req.pty} 소식이 있네요. 빗길/눈길 조심하세요!",
            f"🏠 지금 {req.pty}가 오고 있습니다. 실내 활동을 추천드려요."
        ])
    elif req.sky == "맑음":
        status_desc = random.choice([
            "☀️ 하늘이 아주 맑고 깨끗합니다! 산책하기 딱 좋은 날씨예요.",
            "😎 파란 하늘이 기분까지 상쾌하게 만들어주네요. 썬글라스는 어떠세요?",
            "🌞 햇살이 가득한 하루입니다. 비타민D 충전하세요!"
        ])
    elif req.sky == "구름많음":
        status_desc = random.choice([
            "☁️ 구름이 조금 지나가고 있어요. 덥지도 춥지도 않은 날씨네요.",
            "🌥️ 하늘에 구름이 그림처럼 떠 있네요. 운치 있는 하루입니다."
        ])
    else: # 흐림
        status_desc = random.choice([
            "☁️ 하늘 빛이 조금 흐리네요. 기분만은 밝게 가져가세요!",
            "☂️ 구름이 잔뜩 꼈어요. 혹시 모르니 작은 우산 하나 챙길까요?"
        ])

    # 기온 코멘트
    temp_val = float(req.temp)
    temp_desc = ""
    if temp_val > 28:
        temp_desc = "🧊 푹푹 찌는 더위입니다. 시원한 물 많이 드세요!"
    elif temp_val > 20:
        temp_desc = "😊 활동하기 참 좋은 포근한 기온이에요."
    elif temp_val > 10:
        temp_desc = "🧥 약간 쌀쌀할 수 있으니 겉옷을 챙기시면 좋겠어요."
    else:
        temp_desc = "🧣 공기가 많이 차갑습니다. 감기 조심하세요!"
    # 최종 대본 조합
    full_script = f"{intro}<br>{status_desc}<br>✅ 현재 기온은 {req.temp}도이며, 강수 확률은 {req.pop}%입니다.<br>{temp_desc}"
    
    return {"script": full_script}



# ================= AI 날씨 DJ =================

class DjRequest(BaseModel):
    pty: str  # 강수형태
    sky: str  # 하늘상태
    hour: int # 현재 시간 (0~23)

@app.post("/dj")
def recommend_music(req: DjRequest):
    is_night = req.hour >= 19 or req.hour <= 6
    
    # 1. 추천 로직 (조건에 따른 플레이리스트 ID 매핑)
    # 유튜브 영상 ID (v= 뒤에 있는 코드)
    
    music_list = []
    
    # (1) 비/눈 올 때
    if req.pty not in ["0", "강수없음", "정보 없음"]:
        music_list = [
            {"id": "O1jGOJOpxf8", "comment": "비 오는 날엔 차분한 Lo-fi 재즈가 딱이죠. ☕"},
            {"id": "PTXcP6EvMB0", "comment": "빗소리와 함께 듣는 감성적인 팝송 어떠세요? 🌧️"},
            {"id": "YG0_Uo_GKh", "comment": "창밖을 보며 듣기 좋은 비 오는 날의 피아노 연주. 🎹"}
        ]
    
    # (2) 맑은 날
    elif req.sky == "맑음":
        if is_night:
            # 맑은 밤
            music_list = [
                {"id": "NSLjsCmlAVw", "comment": "밤하늘의 별을 보며 듣는 Chill-hop. 🌙"},
                {"id": "hGrIgIfCxP0", "comment": "편안한 밤을 위한 Lofi Hip Hop Radio. 🎧"},
                {"id": "hiMoy4pyAl0", "comment": "새벽 감성에 어울리는 잔잔한 R&B. 🍷"}
            ]
        else:
            # 맑은 낮
            music_list = [
                {"id": "DRdAgeHuL_g", "comment": "햇살 좋은 날! 신나는 드라이브 뮤직 어때요? 🚗"},
                {"id": "WKGiY3gBXTo", "comment": "기분 전환을 위한 상큼한 K-Pop 플레이리스트! 🎶"},
                {"id": "xZiruNde2Po", "comment": "맑은 오후에 어울리는 어쿠스틱 기타 선율. 🎸"}
            ]
            
    # (3) 흐림/구름많음
    else:
        music_list = [
            {"id": "3kZd1kHf8bU", "comment": "흐린 날엔 센치한 인디 음악이 제격이죠. 🌫️"},
            {"id": "DcgJucctt5Q", "comment": "카페에서 듣기 좋은 부드러운 재즈. 🎷"},
            {"id": "cqf0Ni3Jo_I", "comment": "구름 낀 하늘과 어울리는 몽환적인 팝. ☁️"}
        ]

    # 랜덤 선택
    selected = random.choice(music_list)
    
    return {
        "videoId": selected["id"],
        "comment": f"[AI DJ] {selected['comment']}"
    }


# 서버 실행
if __name__ == "__main__":
    uvicorn.run(app, host="0.0.0.0", port=5000)