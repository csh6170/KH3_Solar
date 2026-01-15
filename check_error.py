import requests
import datetime
import json

# ⚠️ 여기에 사용 중인 인증키를 넣으세요
SERVICE_KEY = "860d22d5afed47ba3bd53eb2e86fb3f152fa17a30ec99d05c043412e5e2d8d05"

# 서울 좌표
nx, ny = "60", "127"
base_date = datetime.datetime.now().strftime("%Y%m%d")
base_time = "0500"

url = "http://apis.data.go.kr/1360000/VilageFcstInfoService_2.0/getVilageFcst"

params = {
    "serviceKey": SERVICE_KEY,
    "pageNo": "1",
    "numOfRows": "10",
    "dataType": "JSON",
    "base_date": base_date,
    "base_time": base_time,
    "nx": nx,
    "ny": ny
}

print("📡 기상청에 요청 보내는 중...")

try:
    # 1. 요청 보내기
    response = requests.get(url, params=params)
    
    # 2. 응답 내용 그대로 출력 (이게 가장 중요합니다!)
    print(f"\n[응답 상태코드]: {response.status_code}")
    print(f"[응답 본문]: {response.text}\n")
    
    # 3. JSON 분석 시도
    res_json = response.json()
    header = res_json.get('response', {}).get('header', {})
    
    print("📢 [기상청의 답변]:", header.get('resultMsg'))
    print("🔢 [결과 코드]:", header.get('resultCode'))

except Exception as e:
    print(f"❌ 파이썬 에러: {e}")