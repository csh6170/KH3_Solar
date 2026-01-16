import sys
import io
import pandas as pd
import joblib
import json
import os
import math
import datetime
import requests
import time

# --- [추가] 터미널 인코딩 에러 방지 설정 (CP949 환경 대응) ---
if sys.stdout.encoding != 'utf-8':
    sys.stdout = io.TextIOWrapper(sys.stdout.detach(), encoding='utf-8')
    sys.stderr = io.TextIOWrapper(sys.stderr.detach(), encoding='utf-8')

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
SERVICE_KEY = "860d22d5afed47ba3bd53eb2e86fb3f152fa17a30ec99d05c043412e5e2d8d05"

def map_to_grid(lat, lon):
    RE = 6371.00877
    GRID = 5.0
    SLAT1 = 30.0
    SLAT2 = 60.0
    OLON = 126.0
    OLAT = 38.0
    XO = 43
    YO = 136

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
    
    theta = lon * DEGRAD - olon
    if theta > math.pi: theta -= 2.0 * math.pi
    if theta < -math.pi: theta += 2.0 * math.pi
    theta *= sn
    
    x = int(ra * math.sin(theta) + XO + 0.5)
    y = int(ro - ra * math.cos(theta) + YO + 0.5)
    
    return x, y

def get_kma_weather_full(lat, lon):
    nx, ny = map_to_grid(lat, lon)
    print(f"[좌표변환] 위도{lat}, 경도{lon} -> NX:{nx}, NY:{ny}")
    
    now = datetime.datetime.now()
    base_date = now.strftime("%Y%m%d")
    tomorrow_str = (now + datetime.timedelta(days=1)).strftime("%Y%m%d")
    
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

    for attempt in range(3):
        try:
            response = requests.get(url, params=params, timeout=10)
            if response.status_code == 429:
                print(f"[지연] 요청 과다로 대기 중... ({attempt+1}/3)")
                time.sleep(2); continue
            
            if response.status_code != 200: return None

            res = response.json()
            items = res['response']['body']['items']['item']
            data = {'TMP': 20.0, 'SKY': 1, 'PTY': 0, 'WSD': 2.0, 'REH': 60.0}
            found = False

            for item in items:
                if item['fcstDate'] == tomorrow_str and item['fcstTime'] == '1200':
                    cat, val = item['category'], item['fcstValue']
                    if cat in ['TMP', 'SKY', 'PTY', 'WSD', 'REH']:
                        try:
                            data[cat] = float(val)
                            found = True
                        except: pass
            
            if not found: print(f"[경고] {tomorrow_str} 12시 데이터가 없습니다.")
            
            sky_code = int(data['SKY'])
            cloud = 0 if sky_code == 1 else (5 if sky_code == 3 else 10)
            rad, sun = calculate_theoretical_radiation(lat, lon, now + datetime.timedelta(days=1), 12, cloud)

            return {
                'temp': data['TMP'], 'cloud': float(cloud), 'wind': data['WSD'],
                'humidity': data['REH'], 'sunshine': sun, 'radiation': rad,
                'snow': 5.0 if data['PTY'] == 3 else 0.0,
                'rain': 5.0 if data['PTY'] in [1,2,4] else 0.0
            }
        except Exception as e:
            print(f"[오류] 시도 {attempt+1} 실패: {e}")
            time.sleep(1)
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
    if len(sys.argv) > 1:
        # [CASE A: Java 연동]
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

    else:
        # [CASE B: 텔레그램 봇]
        print("[System] 봇 구동 준비 중...")
        try:
            from telegram import Update
            from telegram.ext import ApplicationBuilder, CommandHandler, ContextTypes
        except ImportError:
            sys.exit(1)

        TOKEN = '7958973119:AAHMFjSkoqXfqBBm3mFvVXcPDq-kzG0ta8A'
        # TOKEN = '8485655386:AAEIaVJ64fdxOW-JeSAcoKijoZ-tWd7EcKg'

        async def predict_command(update: Update, context: ContextTypes.DEFAULT_TYPE):
            now_str = datetime.datetime.now().strftime("%H:%M:%S")
            try:
                user_input = context.args 
                if len(user_input) < 2:
                    await update.message.reply_text("[안내] 사용법: /how [지역] [용량]")
                    return

                region_name = user_input[0]
                try: capacity = float(user_input[1])
                except: await update.message.reply_text("[오류] 용량은 숫자여야 합니다."); return

                coords = REGION_MAP.get(region_name)
                if not coords:
                    await update.message.reply_text("[오류] 지원하지 않는 지역입니다.")
                    return

                print(f"[{now_str}] [조회] 날씨 분석 및 발전량 계산 시작... ({region_name})")
                await update.message.reply_text(f"[분석] {region_name}의 내일 기상 데이터를 분석 중입니다...")
                
                weather_data = get_kma_weather_full(coords['lat'], coords['lon'])
                
                if not weather_data:
                    weather_data = {'temp':20, 'cloud':5, 'wind':2, 'humidity':60, 'sunshine':0.5, 'radiation':2.5, 'snow':0, 'rain':0}
                    source = "기본값 (주의: 기상청 API 연결 실패)"
                else:
                    source = "기상청 API + 천문 알고리즘"

                gen, _ = calculate_solar_engine(coords['lat'], coords['lon'], weather_data, capacity)
                profit = int(gen * 150)

                await update.message.reply_text(
                    f"[분석 결과] {region_name} {capacity}kW 발전 예측\n"
                    f"데이터 출처: {source}\n"
                    f"예상 기온: {weather_data['temp']}도 / 구름양: {weather_data['cloud']}\n"
                    f"-------------------------------\n"
                    f"내일 예상 발전량: {gen} kWh\n"
                    f"예상 수익: 약 {format(profit, ',')} 원"
                )
                print(f"[{now_str}] [성공] {region_name} 결과 발송 완료")

            except Exception as e:
                print(f"[에러] 발생: {e}")
                await update.message.reply_text("[오류] 계산 도중 에러가 발생했습니다.")

        app = ApplicationBuilder().token(TOKEN).build()
        app.add_handler(CommandHandler("how", predict_command))
        app.run_polling()