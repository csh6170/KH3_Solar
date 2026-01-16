package com.solar;

import jakarta.annotation.PreDestroy;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.stereotype.Component;

@SpringBootApplication
public class CalculatorApplication {

    public static void main(String[] args) {
        SpringApplication.run(CalculatorApplication.class, args);
    }

}





// ============== AI 서버 프로세스 관리자 ==============
// 스프링 부트 실행 시 파이썬 서버를 켜고, 종료 시 같이 끕니다.

@Component
class AiServerManager {
    private Process pythonProcess;

    // 생성자에서 바로 실행하거나, @PostConstruct를 사용해도 됨
    public AiServerManager() {
        startAiServer();
    }


    private void startAiServer() {
        try {
            // [중요] 파이썬 실행 명령어 및 경로 설정
            // 가상환경을 쓴다면 "venv/bin/python" 처럼 전체 경로를 입력해야 할 수도 있음
            // run_all.py가 프로젝트 루트(build.gradle이 있는 곳)에 있음으로 경로 설정
            ProcessBuilder builder = new ProcessBuilder("python", "run_all.py");

            // 파이썬 서버의 로그를 자바 콘솔에도 같이 출력하게 설정
            builder.inheritIO();

            pythonProcess = builder.start();
            System.out.println("🚀 [Auto-Start] Python AI Server가 시작되었습니다. (PID: " + pythonProcess.pid() + ")");

        } catch (Exception e) {
            System.err.println("❌ Python AI Server 시작 중 오류 발생: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // 애플리케이션 종료 시 실행됨
    @PreDestroy
    public void stopAiServer() {
        if (pythonProcess != null && pythonProcess.isAlive()) {
            pythonProcess.destroy(); // 프로세스 종료 시그널 전송
            System.out.println("🛑 [Auto-Stop] Python AI Server가 종료되었습니다.");
        }
    }
    // ============= AI 서버 프로세스 관리자 끝 =============
}