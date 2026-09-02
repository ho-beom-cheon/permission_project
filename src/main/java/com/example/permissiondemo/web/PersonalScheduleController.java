package com.example.permissiondemo.web;
import java.time.LocalDate;
import java.util.List;
import com.example.permissiondemo.directory.PersonalScheduleService;
import com.example.permissiondemo.storage.StateBoundary;
import org.springframework.web.bind.annotation.*;

@RestController
@StateBoundary
@RequestMapping("/api/me/schedules")
public class PersonalScheduleController {
    private final PersonalScheduleService schedules;
    public PersonalScheduleController(PersonalScheduleService schedules){this.schedules=schedules;}
    @GetMapping public ApiResponse<List<PersonalScheduleService.Schedule>> list(@RequestParam LocalDate from,@RequestParam LocalDate to){return ApiResponse.ok(schedules.list(from,to));}
    @PostMapping public ApiResponse<PersonalScheduleService.Schedule> create(@RequestBody PersonalScheduleService.WriteSchedule value){return ApiResponse.ok(schedules.save(0,value));}
    @PutMapping("/{id}") public ApiResponse<PersonalScheduleService.Schedule> update(@PathVariable long id,@RequestBody PersonalScheduleService.WriteSchedule value){return ApiResponse.ok(schedules.save(id,value));}
    @DeleteMapping("/{id}") public ApiResponse<Void> delete(@PathVariable long id,@RequestParam long version){schedules.delete(id,version);return ApiResponse.ok(null);}
}
