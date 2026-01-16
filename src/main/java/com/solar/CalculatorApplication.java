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
            // 경로 문제일 수 있으므로 절대 경로를 사용하거나 프로젝트 루트 확인
            ProcessBuilder builder = new ProcessBuilder("python", "run_all.py");

            // 현재 작업 디렉토리를 프로젝트 루트로 강제 설정
            builder.directory(new java.io.File(System.getProperty("user.dir")));

            builder.inheritIO();
            pythonProcess = builder.start();

            System.out.println("🚀 [Auto-Start] 서버 기동 시도 (PID: " + pythonProcess.pid() + ")");
            Thread.sleep(7000); // 로딩 시간이 길 수 있으니 7초로 늘려봄

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // 애플리케이션 종료 시 실행됨
    @PreDestroy
    public void stopAiServer() {
        if (pythonProcess != null && pythonProcess.isAlive()) {
            try {
                // 1. 현재 실행 중인 파이썬 프로세스의 PID 추출
                String pid = String.valueOf(pythonProcess.pid());

                // 2. Windows 명령어로 트리(/T) 전체를 강제(/F) 종료
                // 이 명령이 실행되면 run_all.py뿐만 아니라 그 자식인 ai_server, predict도 모두 종료됩니다.
                Process killProcess = Runtime.getRuntime().exec("taskkill /F /T /PID " + pid);

                // 3. 종료 명령이 완료될 때까지 최대 5초 대기 (응답 없음 방지)
                killProcess.waitFor();

                System.out.println("🛑 [Auto-Stop] Python AI Server 및 모든 자식 프로세스가 정리되었습니다. (PID: " + pid + ")");

            } catch (Exception e) {
                // 만약 taskkill 명령이 실패할 경우를 대비한 예외 처리
                System.err.println("❌ 강제 종료 중 오류 발생, 일반 종료를 시도합니다: " + e.getMessage());
                pythonProcess.destroyForcibly();
            }
        }
    }
    // ============= AI 서버 프로세스 관리자 끝 =============
}