package com.plumelog.server.config;

import com.alibaba.fastjson.JSONObject;
import com.plumelog.server.controller.MainController;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;

import static org.junit.Assert.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

public class LoginControllerTest {
    private MockMvc mvc;
    private String originalUsername;
    private String originalPassword;

    @Before
    public void setUp() {
        originalUsername = InitConfig.loginUsername;
        originalPassword = InitConfig.loginPassword;
        InitConfig.loginUsername = "test-user";
        InitConfig.loginPassword = "test-password";
        TestInterceptorRegistry registry = new TestInterceptorRegistry();
        new WebConfiguration().addInterceptors(registry);
        mvc = MockMvcBuilders.standaloneSetup(new MainController())
                .addInterceptors(registry.registeredInterceptors()).build();
    }

    @After
    public void tearDown() {
        InitConfig.loginUsername = originalUsername;
        InitConfig.loginPassword = originalPassword;
    }

    @Test
    public void rootLoginCreatesSession() throws Exception {
        assertLogin("/login");
    }

    @Test
    public void prefixedLoginCreatesSession() throws Exception {
        assertLogin("/plumelog/login");
    }

    @Test
    public void prefixedLoginRejectsWrongPassword() throws Exception {
        MvcResult result = mvc.perform(post("/plumelog/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"test-user\",\"password\":\"wrong-password\"}")).andReturn();
        assertEquals(Integer.valueOf(402), JSONObject.parseObject(result.getResponse().getContentAsString()).getInteger("code"));
        assertNull(result.getRequest().getSession().getAttribute("token"));
    }

    @Test
    public void prefixedLogoutAllowsAnonymousRequest() throws Exception {
        MvcResult result = mvc.perform(post("/plumelog/logout")).andReturn();
        assertEquals(200, result.getResponse().getStatus());
        assertEquals("", result.getResponse().getContentAsString());
    }

    @Test
    public void protectedEndpointStillRequiresLogin() throws Exception {
        MvcResult result = mvc.perform(post("/plumelog/getlog")).andReturn();
        assertEquals(Integer.valueOf(401), JSONObject.parseObject(result.getResponse().getContentAsString()).getInteger("code"));
    }

    private void assertLogin(String path) throws Exception {
        MvcResult result = mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"test-user\",\"password\":\"test-password\"}")).andReturn();
        assertEquals(200, result.getResponse().getStatus());
        assertNull(JSONObject.parseObject(result.getResponse().getContentAsString()).getInteger("code"));
        assertNotNull(result.getRequest().getSession().getAttribute("token"));
    }

    private static class TestInterceptorRegistry extends InterceptorRegistry {
        HandlerInterceptor[] registeredInterceptors() {
            return getInterceptors().toArray(new HandlerInterceptor[0]);
        }
    }
}
