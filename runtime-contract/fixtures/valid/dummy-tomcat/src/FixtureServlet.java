package com.codynwave.atenea.fixture;

import java.io.IOException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

public final class FixtureServlet extends HttpServlet {
    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        response.setContentType("application/json");
        response.getWriter().write(
                "{\"fixture\":\"dummy-tomcat\",\"status\":\"UP\",\"runtime\":\"java8-tomcat8\"}");
    }
}
