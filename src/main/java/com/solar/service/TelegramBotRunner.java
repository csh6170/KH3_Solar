package com.solar.service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Service
public class TelegramBotRunner {

    private Process pythonBotProcess; // 텔레그램 봇 프로세스
    private Process pythonApiProcess; // FastAPI 서버 프로세스

    // ✅ 팀원들을 위해 자동으로 설치할 필수 라이브러리 목록
    private final List<String> REQUIRED_PACKAGES = Arrays.asList(
            "fastapi", "uvicorn", "pandas", "scikit-learn",
            "joblib", "requests", "geopy", "python-telegram-bot", "numpy"
    );

    @PostConstruct
    public void startPythonScripts() {
        System.out.println("🚀 [System] Spring Boot와 함께 파이썬 서비스들을 시작합니다...");

        // 1. 기존 좀비 프로세스 정리 (Clean Start)
        killZombiePython();

        // 2. 라이브러리 자동 설치 (New!)
        installLibraries();

        String projectPath = System.getProperty("user.dir");

        // ---------------------------------------------------------
        // 1. 텔레그램 봇 실행 (predict.py)
        // ---------------------------------------------------------
        try {
            String botScriptPath = projectPath + File.separator + "predict.py";
            // -u 옵션: 로그 버퍼링 없이 즉시 출력
            ProcessBuilder pbBot = new ProcessBuilder("python", "-u", botScriptPath);
            pbBot.redirectErrorStream(true);
            pythonBotProcess = pbBot.start();

            // 로그 출력 스레드 (Bot)
            startLogger(pythonBotProcess, "[🐍Bot]");

        } catch (Exception e) {
            System.err.println("❌ 텔레그램 봇 실행 실패: " + e.getMessage());
        }

        // ---------------------------------------------------------
        // 2. AI API 서버 실행 (ai_server.py)
        // ---------------------------------------------------------
        try {
            String apiScriptPath = projectPath + File.separator + "ai_server.py";
            ProcessBuilder pbApi = new ProcessBuilder("python", "-u", apiScriptPath);
            pbApi.redirectErrorStream(true);
            pythonApiProcess = pbApi.start();

            // 로그 출력 스레드 (API)
            startLogger(pythonApiProcess, "[📡API]");

        } catch (Exception e) {
            System.err.println("❌ AI 서버 실행 실패: " + e.getMessage());
        }
    }

    // ✅ [핵심 기능] 파이썬 라이브러리 자동 설치
    private void installLibraries() {
        System.out.println("📦 [Install] 파이썬 라이브러리 상태를 점검합니다...");
        try {
            // 명령어 생성: python -m pip install 패키지1 패키지2 ...
            List<String> command = new ArrayList<>();
            command.add("python");
            command.add("-m");
            command.add("pip");
            command.add("install");
            command.addAll(REQUIRED_PACKAGES);

            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);
            Process process = pb.start();

            // 설치 로그 읽기 (이미 설치된 경우 'Requirement already satisfied'가 뜹니다)
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    // 로그가 너무 많으면 지저분하므로, 설치되는 내용만 출력하거나 주석 처리 가능
                    System.out.println("[📦Pip] " + line);
                }
            }

            process.waitFor(); // 설치 끝날 때까지 대기
            System.out.println("✅ [Install] 라이브러리 준비 완료!");

        } catch (Exception e) {
            System.err.println("⚠️ 라이브러리 설치 중 경고 (이미 설치되어 있다면 무시하세요): " + e.getMessage());
        }
    }

    // 로그 출력 헬퍼 메소드
    private void startLogger(Process process, String prefix) {
        Thread thread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    System.out.println(prefix + " " + line);
                }
            } catch (IOException e) {
                // 프로세스 종료 시 무시
            }
        });
        thread.setDaemon(true);
        thread.start();
    }

    // 좀비 프로세스 정리
    private void killZombiePython() {
        try {
            System.out.println("🧹 [Cleanup] 기존 파이썬 프로세스 정리 중...");
            Process killer = Runtime.getRuntime().exec("taskkill /F /IM python.exe");
            killer.waitFor();
            System.out.println("✨ [Cleanup] 정리 완료.");
        } catch (Exception e) {
            // 무시
        }
    }

    @PreDestroy
    public void stopPythonScripts() {
        if (pythonBotProcess != null && pythonBotProcess.isAlive()) {
            System.out.println("🛑 [System] 텔레그램 봇 종료 중...");
            pythonBotProcess.destroy();
        }
        if (pythonApiProcess != null && pythonApiProcess.isAlive()) {
            System.out.println("🛑 [System] AI API 서버 종료 중...");
            pythonApiProcess.destroy();
        }
        killZombiePython();
    }
}