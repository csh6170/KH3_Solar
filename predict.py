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
from geopy.geocoders import Nominatim
from telegram import Update
from telegram.ext import ApplicationBuilder, CommandHandler, ContextTypes

# ==================================================================================
# [기본 설정] 인코딩 및 버퍼링 강제 설정
# ==================================================================================
sys.stdout = io.TextIOWrapper(sys.stdout.detach(), encoding='utf-8', line_buffering=True)
sys.stderr = io.TextIOWrapper(sys.stderr.detach(), encoding='utf-8', line_buffering=True)
# ==================================================================================

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
# 2. 일사량 계산기
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
# 3. 기상청 API
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
    # print(f"[좌표변환] 위도{lat}, 경도{lon} -> NX:{nx}, NY:{ny}") # 주석 처리됨

    now = datetime.datetime.now()
    base_date = now.strftime("%Y%m%d")
    tomorrow_str = (now + datetime.timedelta(days=1)).strftime("%Y%m%d")

    url = "http://apis.data.go.kr/1360000/VilageFcstInfoService_2.0/getVilageFcst"
    params = {
        "serviceKey": SERVICE_KEY,
        "pageNo": "1",
        "numOfRows": "1000", # 넉넉하게 1000개 요청
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
                time.sleep(2); continue
            if response.status_code != 200: return None

            res = response.json()
            items = res['response']['body']['items']['item']

            # [수정] 최저(TMN), 최고(TMX) 찾기 위한 변수
            min_temp = None
            max_temp = None

            # 태양광 계산용 12시 데이터
            data_12 = {'SKY': 1, 'PTY': 0, 'WSD': 2.0, 'REH': 60.0}

            found_12 = False

            for item in items:
                if item['fcstDate'] == tomorrow_str:
                    cat = item['category']
                    val = item['fcstValue']

                    # 1. 최저기온 (TMN)
                    if cat == 'TMN':
                        min_temp = float(val)

                    # 2. 최고기온 (TMX)
                    if cat == 'TMX':
                        max_temp = float(val)

                    # 3. 낮 12시 데이터 (구름, 습도 등 태양광 효율 계산용)
                    if item['fcstTime'] == '1200':
                        if cat in ['SKY', 'PTY', 'WSD', 'REH']:
                            data_12[cat] = float(val)
                            found_12 = True

            if min_temp is None: min_temp = 0.0 # 예외처리
            if max_temp is None: max_temp = 20.0 # 예외처리

            # AI 모델에 넣을 '기온'은 (최저+최고)/2 평균값 사용
            avg_temp = (min_temp + max_temp) / 2.0

            sky_code = int(data_12['SKY'])
            cloud = 0 if sky_code == 1 else (5 if sky_code == 3 else 10)
            rad, sun = calculate_theoretical_radiation(lat, lon, now + datetime.timedelta(days=1), 12, cloud)

            return {
                'temp': avg_temp,     # 계산용 평균 기온
                'min_temp': min_temp, # [추가] 표시용 최저
                'max_temp': max_temp, # [추가] 표시용 최고
                'cloud': float(cloud),
                'wind': data_12['WSD'],
                'humidity': data_12['REH'],
                'sunshine': sun,
                'radiation': rad,
                'snow': 5.0 if data_12['PTY'] == 3 else 0.0,
                'rain': 5.0 if data_12['PTY'] in [1,2,4] else 0.0
            }
        except Exception as e:
            time.sleep(1)
    return None

def get_lat_lon_from_address(address):
    geolocator = Nominatim(user_agent="Solar_Power_Bot_v1")
    try:
        location = geolocator.geocode(f"대한민국 {address}")
        if location: return location.latitude, location.longitude
        return None, None
    except: return None, None

# ---------------------------------------------------------
# 4. 좌표 맵 (테스트용 상세 좌표 포함)
# ---------------------------------------------------------
REGION_MAP = {
    "서울": {"lat": 37.5665, "lon": 126.9780},
    "부산": {"lat": 35.1796, "lon": 129.0756},
    "당진": {"lat": 37.0507, "lon": 126.5103},
    "서울 종로구": {"lat": 37.5730, "lon": 126.9794},
    "울산": {"lat": 35.5384, "lon": 129.3114},
    "울산 울주군": {"lat": 35.5222, "lon": 129.2424},
    "울산 남구": {"lat": 35.5436, "lon": 129.3303}
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
        # [CASE A: Java 웹 연동]
        try:
            lat = float(sys.argv[9])
            lon = float(sys.argv[10])
            weather_data = get_kma_weather_full(lat, lon)

            if weather_data is None:
                weather_data = {
                    'temp': float(sys.argv[1]), 'cloud': float(sys.argv[2]),
                    'wind': float(sys.argv[3]), 'humidity': float(sys.argv[4]),
                    'sunshine': float(sys.argv[5]), 'radiation': float(sys.argv[6]),
                    'snow': float(sys.argv[7]), 'rain': float(sys.argv[8])
                }

            total_gen, hourly_logs = calculate_solar_engine(lat, lon, weather_data, capacity_kw=1.0)
            print(json.dumps({ "total": total_gen, "hourly": hourly_logs }))

        except Exception as e:
            print(json.dumps({"error": str(e)}))

    else:
        # [CASE B: 텔레그램 봇]
        print("[System] 봇 구동 준비 중...")
        try:
            from telegram import Update
            from telegram.ext import ApplicationBuilder, CommandHandler, ContextTypes
        except ImportError: sys.exit(1)

        TOKEN = '8485655386:AAEIaVJ64fdxOW-JeSAcoKijoZ-tWd7EcKg'
        #TOKEN = '7958973119:AAHMFjSkoqXfqBBm3mFvVXcPDq-kzG0ta8A'

        async def predict_command(update: Update, context: ContextTypes.DEFAULT_TYPE):
            now_str = datetime.datetime.now().strftime("%H:%M:%S")
            try:
                user_input = context.args
                if len(user_input) < 2:
                    await update.message.reply_text("[안내] 사용법: /how [지역명] [용량]\n예: /how 울산 남구 3")
                    return

                try: capacity = float(user_input[-1])
                except ValueError:
                    await update.message.reply_text("[오류] 용량은 숫자여야 합니다.")
                    return

                region_name = " ".join(user_input[:-1])
                lat, lon = 0.0, 0.0
                if region_name in REGION_MAP:
                    lat = REGION_MAP[region_name]['lat']
                    lon = REGION_MAP[region_name]['lon']
                else:
                    await update.message.reply_text(f"[검색] '{region_name}' 위치 찾는 중...")
                    found_lat, found_lon = get_lat_lon_from_address(region_name)
                    if found_lat: lat, lon = found_lat, found_lon
                    else:
                        await update.message.reply_text("[오류] 위치를 찾을 수 없습니다.")
                        return

                print(f"[{now_str}] [조회] {region_name} ({lat:.2f}, {lon:.2f})")
                await update.message.reply_text(f"[분석] {region_name}의 내일 날씨 데이터를 분석 중입니다...")

                weather_data = get_kma_weather_full(lat, lon)
                if not weather_data:
                    await update.message.reply_text("[오류] 기상청 데이터 실패.")
                    return

                gen, _ = calculate_solar_engine(lat, lon, weather_data, capacity)
                profit = int(gen * 120)

                cloud_val = weather_data['cloud']
                cloud_text = "맑음 ☀️" if cloud_val <= 2 else ("구름 조금 🌤️" if cloud_val <= 5 else ("구름 많음 ☁️" if cloud_val <= 8 else "흐림 ☁️"))

                # [수정] 최저/최고 기온 표시
                min_t = weather_data.get('min_temp', '?')
                max_t = weather_data.get('max_temp', '?')

                await update.message.reply_text(
                    f"[분석 결과] {region_name} 태양광 예측\n"
                    f"- 설비 용량: {capacity} kW\n"
                    f"-------------------------------\n"
                    f"- 내일 기온: 최저 {min_t}°C / 최고 {max_t}°C\n"
                    f"- 하늘 상태: {cloud_text} ({cloud_val}/10)\n"
                    f"-------------------------------\n"
                    f"* 예상 발전량: {gen} kWh\n"
                    f"* 예상 수익: 약 {format(profit, ',')} 원"
                )
                print(f"[{now_str}] [성공] 발송 완료")

            except Exception as e:
                print(f"[에러] {e}")
                await update.message.reply_text("[오류] 에러 발생.")

        app = ApplicationBuilder().token(TOKEN).build()
        app.add_handler(CommandHandler("how", predict_command))
        app.run_polling()