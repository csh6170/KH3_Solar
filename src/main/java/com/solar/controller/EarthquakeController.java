package com.solar.controller;

import com.solar.dto.EarthquakeDTO;
import com.solar.service.WeatherService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.List;

@Controller
@RequiredArgsConstructor
public class EarthquakeController {

    private final WeatherService weatherService;

    @GetMapping("/earthquake")
    public String earthquakePage(Model model) {
        List<EarthquakeDTO> list = weatherService.getEarthquakeList();
        model.addAttribute("list", list);
        return "earthquake";
    }
}