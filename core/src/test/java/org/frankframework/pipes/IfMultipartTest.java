package org.frankframework.pipes;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.frankframework.core.PipeForward;
import org.frankframework.core.PipeRunException;
import org.frankframework.core.PipeRunResult;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockitoAnnotations;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 *
 * IfMultipart Tester.
 *
 * @author <Sina Sen>
 */
public class IfMultipartTest extends PipeTestBase<IfMultipart> {

	private MockHttpServletRequest request;

	@BeforeEach
	public void before() {
		request = new MockHttpServletRequest();
		MockitoAnnotations.initMocks(this);
	}

	@Override
	public IfMultipart createPipe() {
		return new IfMultipart();
	}

	@Test
	void testInputNullElseForwardNull() throws Exception {
		pipe.setElseForwardName(null);
		configureAndStartPipe();

		PipeRunException e = assertThrows(PipeRunException.class, ()->doPipe(pipe, null, session));
		assertThat(e.getMessage(), Matchers.endsWith("cannot find forward or pipe named [null]"));
	}

	@Test
	void testInputNotHTTPRequest() throws Exception {
		configureAndStartPipe();

		PipeRunException e = assertThrows(PipeRunException.class, ()->doPipe(pipe, "i am a string not a http req", session));
		assertThat(e.getMessage(), Matchers.endsWith("expected HttpServletRequest as input, got [Message]"));
	}

	@Test
	void testRequestUsesElseForward() throws Exception {
		PipeForward forw = new PipeForward("custom_else", "random/path");
		pipe.registerForward(forw);
		pipe.setElseForwardName("custom_else");
		configureAndStartPipe();

		PipeRunResult res = doPipe(pipe, request, session);
		PipeForward forward = res.getPipeForward();
		assertEquals("custom_else", forward.getName());
	}

	@Test
	void testRequestUsesThenForward() throws Exception {
		request.setContentType("multipartofx");
		pipe.setThenForwardName("success");
		configureAndStartPipe();

		PipeRunResult res = doPipe(pipe, request, session);
		PipeForward forward = res.getPipeForward();
		assertEquals("success", forward.getName());
	}

	@Test
	void testRequestContentTypeWrong() throws Exception {
		request.setContentType("aamultipartofx");
		pipe.setThenForwardName("success");
		configureAndStartPipe();

		assertThrows(PipeRunException.class, ()->doPipe(pipe, request, session));
	}

	@Test
	void testCannotFindForward() throws Exception {
		pipe.setElseForwardName("elsee");
		configureAndStartPipe();

		assertThrows(PipeRunException.class, ()->doPipe(pipe, request, session));
	}
}
