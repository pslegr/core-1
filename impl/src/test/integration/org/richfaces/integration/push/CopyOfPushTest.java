package org.richfaces.integration.push;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.net.URL;

import javax.servlet.http.HttpServletRequest;

import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.container.test.api.RunAsClient;
import org.jboss.arquillian.drone.api.annotation.Drone;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.jboss.arquillian.warp.ClientAction;
import org.jboss.arquillian.warp.HttpRequest;
import org.jboss.arquillian.warp.RequestFilter;
import org.jboss.arquillian.warp.ServerAssertion;
import org.jboss.arquillian.warp.Warp;
import org.jboss.arquillian.warp.WarpTest;
import org.jboss.arquillian.warp.extension.servlet.AfterServlet;
import org.jboss.arquillian.warp.extension.servlet.BeforeServlet;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.openqa.selenium.WebDriver;
import org.richfaces.application.push.MessageException;
import org.richfaces.application.push.PushContext;
import org.richfaces.application.push.PushContextFactory;
import org.richfaces.application.push.Session;
import org.richfaces.application.push.SessionManager;
import org.richfaces.application.push.TopicKey;
import org.richfaces.application.push.TopicsContext;
import org.richfaces.integration.CoreDeployment;
import org.richfaces.log.Logger;
import org.richfaces.log.RichfacesLogger;
import org.richfaces.shrinkwrap.descriptor.FaceletAsset;
import org.richfaces.wait.Condition;
import org.richfaces.wait.Wait;
import org.richfaces.webapp.PushHandlerFilter;

@RunWith(Arquillian.class)
@WarpTest
public class CopyOfPushTest {

    @Drone
    WebDriver driver;

    @ArquillianResource
    URL contextPath;

    @Deployment
    public static WebArchive createDeployment() {

        CoreDeployment deployment = new CoreDeployment(CopyOfPushTest.class);


        deployment.addMavenDependency(
                "org.richfaces.ui.common:richfaces-ui-common-api",
                "org.richfaces.ui.common:richfaces-ui-common-ui",
                "org.richfaces.ui.core:richfaces-ui-core-api",
                "org.richfaces.ui.core:richfaces-ui-core-ui");

        FaceletAsset pushPage = new FaceletAsset().body("<a4j:push address=\"" + Commons.TOPIC + "\" />");

        deployment.archive()
                /** ROOT */
                .addAsWebResource(pushPage, "push1.xhtml")
                .addAsWebResource(pushPage, "push2.xhtml")
                .addAsResource("META-INF/resources/richfaces-event.js")
                .addAsResource("META-INF/resources/jquery.js")
                .addAsResource("META-INF/resources/richfaces.js")
                .addAsResource("META-INF/resources/richfaces-queue.js")
                .addAsResource("META-INF/resources/richfaces-base-component.js");

        deployment.withResourceHandler();
        deployment.withDependencyInjector();
        deployment.withResourceCodec();
        deployment.withResourceLibraries();
        deployment.withPush();

        return deployment.getFinalArchive();
    }

    @Test
    @RunAsClient
    public void test_push() {

        // open first page and wait for push
        FirstPushAssertion firstPushAssertion = Warp.filter(new UriRequestFilter("__richfaces_push")).execute(new ClientAction() {
            public void action() {
                driver.navigate().to(contextPath + "push1.jsf");
            }
        }).verify(new FirstPushAssertion());
        
        // get a first push sessionId
        String firstPushSessionId = firstPushAssertion.getSessionId();
        assertNotNull("first push sessionId must not be null", firstPushSessionId);
        
        SecondPushAssertion secondPushAssertion = new SecondPushAssertion(firstPushSessionId);
        
        // switch page and wait for push
        Warp.filter(new SessionIdNotMatching("__richfaces_push", firstPushSessionId)).execute(new ClientAction() {
            public void action() {
                driver.navigate().to(contextPath + "push2.jsf");
            }
        }).verify(secondPushAssertion);
        
        
        
        
    }

    // TODO should be part of Phaser
    public static class UriRequestFilter implements RequestFilter<HttpRequest> {

        private String uriPart;

        public UriRequestFilter(String uriPart) {
            this.uriPart = uriPart;
        }

        @Override
        public boolean matches(HttpRequest httpRequest) {
            String uri = httpRequest.getUri();
            boolean matches = uri.contains(uriPart);
            return matches;
        }
    }
    
    public static class SessionIdNotMatching extends UriRequestFilter implements RequestFilter<HttpRequest> {

        private String oldSessionId;

        public SessionIdNotMatching(String uriPart, String oldSessionId) {
            super(uriPart);
            this.oldSessionId = oldSessionId;
        }

        @Override
        public boolean matches(HttpRequest httpRequest) {
            String uri = httpRequest.getUri();
            boolean matches = super.matches(httpRequest) && !uri.contains(oldSessionId);
            return matches;
        }
    }

    public static class FirstPushAssertion extends PushAssertion {

        private static final long serialVersionUID = 1L;
        
        private String sessionId;

        public String getSessionId() {
            return sessionId;
        }
        
        @BeforeServlet
        public void beforeServlet() throws Exception {
            LOGGER.debug("beforeServlet");
            
            Session session = getCurrentSession();
            sessionId = session.getId();
            
            assertEquals("messages for current session must be empty before pushing", 0, session.getMessages().size());

            // TODO should be invokable by separate session
            sendMessage("foo");
        }

        @AfterServlet
        public void afterServlet() throws MessageException {
            LOGGER.debug("afterServlet");
            
            // TODO instead of waiting, we should be able intercept Atmosphere's onBroaddcast/.. methods
            new Wait()
                .failWith("messages for current session must be cleared (empty) after pushing")
                .until(new MessagesAreEmpty());
            
            sendMessage("new message");
        }
    }
    
    public static class SecondPushAssertion extends PushAssertion {

        private static final long serialVersionUID = 1L;
        
        private String oldSessionId;
        
        public SecondPushAssertion(String sessionId) {
            this.oldSessionId = sessionId;
        }
        
        @BeforeServlet
        public void beforeServlet() throws Exception {
            LOGGER.debug("beforeServlet");
            
            sendMessage("foo");
            
            Session session = getCurrentSession();
            String currentSessionId = session.getId();
            
            assertFalse("old and new session id must not equal", currentSessionId.equals(oldSessionId));
            
            
            Session oldSession = getSessionWithId(oldSessionId);
            assertNotNull("old session must not be destroyed", oldSession);
            
            int oldSessionMessageCount = oldSession.getMessages().size();
            
            assertTrue("there must be one or two messages undelivered for old session", oldSessionMessageCount == 1 || oldSessionMessageCount == 2);
        }
    }
    
    private abstract static class PushAssertion extends ServerAssertion {

        private static final long serialVersionUID = 1L;
        
        static final Logger LOGGER = RichfacesLogger.TEST.getLogger();

        @ArquillianResource
        HttpServletRequest request;

        @ArquillianResource
        PushContextFactory pushContextFactory;
        
        class MessagesAreEmpty implements Condition {
            @Override
            public boolean isTrue() {
                final Session session = getCurrentSession();
                return session.getMessages().size() == 0;
            }
        }
        
        Session getSessionWithId(String pushSessionId) {
            PushContext pushContext = pushContextFactory.getPushContext();
            SessionManager sessionManager = pushContext.getSessionManager();
            Session session = sessionManager.getPushSession(pushSessionId);
            return session;
        }

        Session getCurrentSession() {
            String pushSessionId = request.getParameter(PushHandlerFilter.PUSH_SESSION_ID_PARAM);
            return getSessionWithId(pushSessionId);
        }

        void sendMessage(String message) throws MessageException {
            TopicsContext topicsContext = TopicsContext.lookup();
            TopicKey topicKey = new TopicKey(Commons.TOPIC);
            topicsContext.getOrCreateTopic(topicKey);
            topicsContext.publish(topicKey, message);
        }
    }

    public static class Commons {
        public static final String TOPIC = "testingTopic";
    }
}
