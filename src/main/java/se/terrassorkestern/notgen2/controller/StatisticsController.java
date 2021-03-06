package se.terrassorkestern.notgen2.controller;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import se.terrassorkestern.notgen2.model.Statistics;
import se.terrassorkestern.notgen2.service.StatisticsService;

@Controller
@RequestMapping("/statistics")
public class StatisticsController {
    static final Logger log = LoggerFactory.getLogger(StatisticsController.class);

    private final StatisticsService statisticsService;


    public StatisticsController(StatisticsService statisticsService) {
        this.statisticsService = statisticsService;
    }

    @GetMapping(value = {"", "/"})
    public String statistics(Model model) {

        Statistics statistics = statisticsService.getStatistics();
        model.addAttribute("statistics", statistics);

        return "statistics";
    }
}