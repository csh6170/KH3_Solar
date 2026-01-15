package com.solar.controller;

import com.solar.dto.TyphoonDTO;
import com.solar.service.WeatherService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;

import java.util.List;

@Controller
@RequiredArgsConstructor
public class TyphoonController {

    private final WeatherService weatherService;

    @GetMapping("/typhoon")
    public String typhoonPage(Model model) {
        List<TyphoonDTO> list = weatherService.getTyphoonList();
        model.addAttribute("list", list);
        return "typhoon";
    }
}