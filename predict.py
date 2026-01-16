import sys
import pandas as pd
import joblib
import json
import os
import math
import datetime
import requests
import time

# ---------------------------------------------------------
# 1. 모델 로드
# ---------------------------------------------------------
current_folder = os.path.dirname(os.path.abspath(__file__))
model_path = os.path.join(current_folder, 'data', 'solar_model.pkl')

if not os.path.exists(model_path):
    print(json.dumps({"error": "모델 파일을 찾을 수 없습니다."}))
    sys.exit(1)

model = joblib.load(model_path)

# ---------------------------------------------------------
# 2. [고급] 천문학적 일사량 계산기
# ---------------------------------------------------------
def calculate_theoretical_radiation(lat, lon, date, hour, cloud_cover_score):
    doy = date.timetuple().tm_yday
    declination = 23.45 * math.sin(math.radians(360 * (284 + doy) / 365))
    hour_angle = (hour - 12) * 15 
    
    lat_rad = math.radians(lat)
    dec_rad = math.radians(declination)
    ha_rad = math.radians(hour_angle)
    
    sin_elevation = (math.sin(lat_rad) * math.sin(dec_rad)) + \
                    (math.cos(lat_rad) * math.cos(dec_rad) * math.cos(ha_rad))
    elevation = math.degrees(math.asin(max(0, sin_elevation)))

    if elevation <= 0: return 0.0, 0.0
    
    max_radiation = 3.6 * math.sin(math.radians(elevation))
    cloud_factor = 1.0 - (cloud_cover_score / 10.0 * 0.7) 
    estimated_radiation = max_radiation * cloud_factor
    estimated_sunshine = 1.0 if cloud_cover_score <= 5 else 0.0
    
    return round(estimated_radiation, 2), estimated_sunshine

# ---------------------------------------------------------
# 3. 기상청 API 연동 (성공한 로직 이식)
# ---------------------------------------------------------

# ⚠️ [중요] check_error.py 에서 성공했던 키를 그대로 복사해오세요!
SERVICE_KEY = "860d22d5afed47ba3bd53eb2e86fb3f152fa17a30ec99d05c043412e5e2d8d05"

def map_to_grid(lat, lon):
    """ 기상청 격자 좌표 변환 (공식 수정됨) """
    RE = 6371.00877  # 지구 반경(km)
    GRID = 5.0       # 격자 간격(km)
    SLAT1 = 30.0     # 투영 위도1(degree)
    SLAT2 = 60.0     # 투영 위도2(degree)
    OLON = 126.0     # 기준점 경도(degree)
    OLAT = 38.0      # 기준점 위도(degree)
    XO = 43          # 기준점 X좌표(GRID)
    YO = 136         # 기준점 Y좌표(GRID)

    DEGRAD = math.pi / 180.0
    
    re = RE / GRID
    slat1 = SLAT1 * DEGRAD
    slat2 = SLAT2 * DEGRAD
    olat = OLAT * DEGRAD
    olon = OLON * DEGRAD
    
    sn = math.tan(math.pi * 0.25 + slat2 * 0.5) / math.tan(math.pi * 0.25 + slat1 * 0.5)
    sn = math.log(math.cos(slat1) / math.cos(slat2)) / math.log(sn)
    sf = math.tan(math.pi * 0.25 + slat1 * 0.5)
    sf = (sf ** sn) * math.cos(slat1) / sn
    ro = math.tan(math.pi * 0.25 + olat * 0.5)
    ro = re * sf / (ro ** sn)
    ra = math.tan(math.pi * 0.25 + (lat) * DEGRAD * 0.5)
    ra = re * sf / (ra ** sn)
    
    # [수정된 부분] theta 계산과 Y좌표 공식이 수정되었습니다.
    theta = lon * DEGRAD - olon
    if theta > math.pi: theta -= 2.0 * math.pi
    if theta < -math.pi: theta += 2.0 * math.pi
    theta *= sn
    
    x = int(ra * math.sin(theta) + XO + 0.5)
    y = int(ro - ra * math.cos(theta) + YO + 0.5)
    
    return x, y

import time # 🕒 시간 지연을 위해 맨 위에 import time 확인 필수!

# [수정] 3. 기상청 API 연동 (강수없음 문자열 에러 해결됨)
def get_kma_weather_full(lat, lon):
    nx, ny = map_to_grid(lat, lon)
    print(f"📍 좌표 변환 결과: 위도{lat}, 경도{lon} -> NX:{nx}, NY:{ny}")
    
    now = datetime.datetime.now()
    base_date = now.strftime("%Y%m%d")
    tomorrow_date = (now + datetime.timedelta(days=1))
    tomorrow_str = tomorrow_date.strftime("%Y%m%d")
    
    url = "http://apis.data.go.kr/1360000/VilageFcstInfoService_2.0/getVilageFcst"
    
    params = {
        "serviceKey": SERVICE_KEY,
        "pageNo": "1",
        "numOfRows": "900", 
        "dataType": "JSON",
        "base_date": base_date,
        "base_time": "0500", 
        "nx": str(nx),
        "ny": str(ny)
    }

    max_retries = 3
    for attempt in range(max_retries):
        try:
            response = requests.get(url, params=params, timeout=10)
            
            if response.status_code == 429:
                print(f"⏳ 요청이 너무 많아 잠시 대기합니다... ({attempt+1}/{max_retries})")
                time.sleep(2)
                continue
            
            if response.status_code != 200:
                print(f"⚠️ API 통신 오류: Status {response.status_code}")
                return None

            res = response.json()
            header = res.get('response', {}).get('header', {})
            if header.get('resultCode') != '00':
                print(f"⚠️ 기상청 에러: {header.get('resultMsg')}")
                return None

            items = res['response']['body']['items']['item']
            
            # [기존] 모든 데이터를 다 담으려다 에러 발생
            # [수정] 필요한 데이터(TMP, SKY, PTY, WSD, REH)만 골라서 담음!
            data = {'TMP': 20.0, 'SKY': 1, 'PTY': 0, 'WSD': 2.0, 'REH': 60.0}
            found = False

            for item in items:
                if item['fcstDate'] == tomorrow_str and item['fcstTime'] == '1200':
                    cat = item['category']
                    val = item['fcstValue']
                    
                    # ⚡ 핵심 수정: 숫자로 변환 가능한 핵심 데이터만 처리
                    if cat in ['TMP', 'SKY', 'PTY', 'WSD', 'REH']:
                        try:
                            data[cat] = float(val)
                            found = True
                        except ValueError:
                            pass # 혹시라도 이상한 문자열이 오면 무시
            
            if not found:
                print(f"⚠️ 데이터 없음 (날짜/시간 확인 필요)")
            
            sky_code = int(data['SKY'])
            if sky_code == 1: cloud = 0
            elif sky_code == 3: cloud = 5
            else: cloud = 10
            
            rad, sun = calculate_theoretical_radiation(lat, lon, tomorrow_date, 12, cloud)

            return {
                'temp': data['TMP'], 'cloud': float(cloud), 'wind': data['WSD'],
                'humidity': data['REH'], 'sunshine': sun, 'radiation': rad,
                'snow': 5.0 if data['PTY'] == 3 else 0.0,
                'rain': 5.0 if data['PTY'] in [1,2,4] else 0.0
            }

        except Exception as e:
            print(f"⚠️ 시도 {attempt+1} 실패: {e}")
            time.sleep(1)

    print("❌ 3번 시도했으나 모두 실패했습니다.")
    return None

# ---------------------------------------------------------
# 4. 핵심 계산 엔진
# ---------------------------------------------------------
REGION_MAP = {
    "서울": {"lat": 37.5665, "lon": 126.9780},
    "부산": {"lat": 35.1796, "lon": 129.0756},
    "당진": {"lat": 37.0507, "lon": 126.5103}
}

def calculate_solar_engine(lat, lon, weather_data, capacity_kw=1.0):
    base_temp = weather_data.get('temp', 20.0)
    cloud = weather_data.get('cloud', 5.0)
    wind = weather_data.get('wind', 2.0)
    humidity = weather_data.get('humidity', 60.0)
    snow = weather_data.get('snow', 0.0)
    rain = weather_data.get('rain', 0.0)
    target_date = datetime.datetime.now() + datetime.timedelta(days=1)

    total_daily_efficiency = 0.0
    hourly_results = []

    for hour in range(6, 20):
        curr_radiation, curr_sunshine = calculate_theoretical_radiation(lat, lon, target_date, hour, cloud)
        curr_temp = base_temp + (2.0 if 12 <= hour <= 15 else -2.0)

        input_data = pd.DataFrame({
            '시간': [hour], '위도': [lat], '경도': [lon],
            'temp': [curr_temp], 'rain': [rain], 'wind': [wind],
            'humidity': [humidity], 'sunshine': [curr_sunshine],
            'radiation': [curr_radiation], 'snow': [snow], 'cloud': [cloud]
        })

        pred = model.predict(input_data)[0]
        if pred < 0: pred = 0.0
            
        total_daily_efficiency += pred
        hourly_results.append({"hour": hour, "value": round(pred, 3)})

    return round(total_daily_efficiency * capacity_kw, 4), hourly_results

# ---------------------------------------------------------
# 5. 메인 실행부
# ---------------------------------------------------------
if __name__ == '__main__':
    # [CASE A: Java 연동]
    if len(sys.argv) > 1:
        try:
            if len(sys.argv) >= 11:
                weather_input = {
                    'temp': float(sys.argv[1]), 'cloud': float(sys.argv[2]),
                    'wind': float(sys.argv[3]), 'humidity': float(sys.argv[4]),
                    'sunshine': float(sys.argv[5]), 'radiation': float(sys.argv[6]),
                    'snow': float(sys.argv[7]), 'rain': float(sys.argv[8])
                }
                lat, lon = float(sys.argv[9]), float(sys.argv[10])
            else:
                weather_input = {'temp':20, 'cloud':5, 'wind':2, 'humidity':60, 'sunshine':0.5, 'radiation':2.5, 'snow':0, 'rain':0}
                lat, lon = 37.0507, 126.5103

            total_gen, hourly_logs = calculate_solar_engine(lat, lon, weather_input, capacity_kw=1.0)
            print(json.dumps({ "total": total_gen, "hourly": hourly_logs }))
        except Exception as e:
            print(json.dumps({"error": str(e)}))

    # [CASE B: 텔레그램 봇]
    else:
        print(" 봇 구동 준비 중...")
        try:
            from telegram import Update
            from telegram.ext import ApplicationBuilder, CommandHandler, ContextTypes
        except ImportError:
            sys.exit(1)

        TOKEN = '7958973119:AAHMFjSkoqXfqBBm3mFvVXcPDq-kzG0ta8A' 

        async def predict_command(update: Update, context: ContextTypes.DEFAULT_TYPE):
            now_str = datetime.datetime.now().strftime("%H:%M:%S")
            try:
                user_input = context.args 
                if len(user_input) < 2:
                    await update.message.reply_text("💡 사용법: /how [지역] [용량]")
                    return

                region_name = user_input[0]
                try: capacity = float(user_input[1])
                except: await update.message.reply_text("❌ 용량은 숫자여야 합니다."); return

                coords = REGION_MAP.get(region_name)
                if not coords:
                    await update.message.reply_text("❌ 지원하지 않는 지역입니다.")
                    return

                print(f"[{now_str}] 📡 날씨 조회 및 정밀 계산 중... ({region_name})")
                await update.message.reply_text(f"📡 {region_name}의 내일 날씨를 조회하고 천문 알고리즘을 수행합니다...")
                
                weather_data = get_kma_weather_full(coords['lat'], coords['lon'])
                
                if not weather_data:
                    # 에러 발생 시 로그를 터미널에서 확인하라고 메시지 변경
                    weather_data = {'temp':20, 'cloud':5, 'wind':2, 'humidity':60, 'sunshine':0.5, 'radiation':2.5, 'snow':0, 'rain':0}
                    source = "기본값 (⚠️API 오류 - 터미널 로그 확인)"
                else:
                    source = "기상청 API + 천문 알고리즘"

                gen, _ = calculate_solar_engine(coords['lat'], coords['lon'], weather_data, capacity)
                profit = int(gen * 150)

                await update.message.reply_text(
                    f"☀️ **{region_name} {capacity}kW 정밀 분석**\n"
                    f"📉 기반: {source}\n"
                    f"🌡️ 기온: {weather_data['temp']}℃ / ☁️ 구름: {weather_data['cloud']}\n"
                    f"-------------------------------\n"
                    f"⚡ 예상 발전량: {gen} kWh\n"
                    f"💰 예상 수익: 약 {format(profit, ',')} 원"
                )
                print(f"[{now_str}] ✅ 발송 완료")

            except Exception as e:
                print(f"❌ 에러: {e}")
                await update.message.reply_text("계산 중 오류 발생")

        app = ApplicationBuilder().token(TOKEN).build()
        app.add_handler(CommandHandler("how", predict_command))
        app.run_polling()
