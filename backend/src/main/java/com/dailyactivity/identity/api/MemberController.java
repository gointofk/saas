package com.dailyactivity.identity.api;

import com.dailyactivity.auth.api.AuthSupport;
import com.dailyactivity.auth.api.RequireMenu;
import com.dailyactivity.common.PageResponse;
import com.dailyactivity.identity.application.MemberService;
import com.dailyactivity.identity.domain.port.MemberRepository;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin/members")
@RequireMenu("members")
public class MemberController {
    private final MemberService service;

    public MemberController(MemberService service) {
        this.service = service;
    }

    @GetMapping
    public PageResponse<MemberRepository.Member> page(@RequestParam(required = false) String q, @RequestParam(required = false) Integer page, HttpServletRequest r) {
        return service.page(AuthSupport.principal(r, "seller"), q, page);
    }

    @PostMapping
    public Map<String, Object> create(@RequestBody MemberService.MemberRequest b, HttpServletRequest r) {
        return service.create(AuthSupport.principal(r, "seller"), b);
    }

    @PutMapping("/{id}")
    public void update(@PathVariable long id, @RequestBody MemberService.MemberRequest b, HttpServletRequest r) {
        service.update(AuthSupport.principal(r, "seller"), id, b);
    }

    @PatchMapping("/{id}/status")
    public void status(@PathVariable long id, @RequestBody Map<String, Boolean> b, HttpServletRequest r) {
        service.status(AuthSupport.principal(r, "seller"), id, Boolean.TRUE.equals(b.get("active")));
    }
}
