/*
   Copyright 2013 Nationale-Nederlanden, 2020, 2022 WeAreFrank!

   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at

       http://www.apache.org/licenses/LICENSE-2.0

   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
*/
package org.frankframework.core;

import org.frankframework.configuration.ConfigurationException;
import org.frankframework.doc.ElementType;
import org.frankframework.doc.ElementType.ElementTypes;
import org.frankframework.doc.FrankDocGroup;
import org.frankframework.stream.Message;

/**
 * The <code>ISender</code> is responsible for sending a message to
 * some destination.
 *
 * @author  Gerrit van Brakel
 */
@FrankDocGroup(order = 20, name = "Senders")
@ElementType(ElementTypes.ENDPOINT)
public interface ISender extends IConfigurable {

	/**
	 * <code>configure()</code> is called once at startup of the framework in the configure method of the owner of this sender.
	 * Purpose of this method is to check whether the static configuration of the sender is correct.
	 * As much as possible class-instantiating should take place in the <code>configure()</code> or <code>open()</code> method, to improve performance.
	 */
	@Override
	void configure() throws ConfigurationException;

	/**
	 * This method will be called to start the sender. After this method is called the sendMessage method may be called.
	 * Purpose of this method is to reduce creating connections to databases etc. in the {@link #sendMessage(Message, PipeLineSession) sendMessage()} method.
	 */
	void open() throws SenderException;

	/**
	 * Stop/close the sender and deallocate resources.
	 */
	void close() throws SenderException;

	/**
	 * When <code>true</code>, the result of sendMessage is the reply of the request.
	 */
	default boolean isSynchronous() {
		return true;
	}

	/**
	 * Send a message to some destination (as configured in the Sender object). This method may only be called after the <code>configure() </code>
	 * method is called.
	 * <p>
	 * The following table shows the difference between synchronous and a-synchronous senders:
	 * <table border="1">
	 * <tr><th>&nbsp;</th><th>synchronous</th><th>a-synchronous</th></tr>
	 * <tr><td>{@link #isSynchronous()} returns</td><td><code>true</code></td><td><code>false</code></td></tr>
	 * <tr><td>return value of <code>sendMessage()</code> is</td><td>the reply-message</td><td>the messageId of the message sent</td></tr>
	 * <tr><td>the correlationID specified with <code>sendMessage()</code></td><td>may be ignored</td><td>is sent with the message</td></tr>
	 * <tr><td>a {link TimeOutException}</td><td>may be thrown if a timeout occurs waiting for a reply</td><td>should not be expected</td></tr>
	 * </table>
	 * <p>
	 * Multiple objects may try to call this method at the same time, from different threads.
	 * Implementations of this method should therefore be thread-safe, or <code>synchronized</code>.
	 */
	SenderResult sendMessage(Message message, PipeLineSession session) throws SenderException, TimeoutException;

	default Message sendMessageOrThrow(Message message, PipeLineSession session) throws SenderException, TimeoutException {
		SenderResult senderResult = sendMessage(message, session);
		Message result = senderResult.getResult();
		if (!senderResult.isSuccess()) {
			SenderException se = new SenderException(senderResult.getErrorMessage());
			try {
				result.close();
			} catch (Exception e) {
				se.addSuppressed(e);
			}
			throw (se);
		}
		return result;
	}

	/**
	 * returns <code>true</code> if the sender or one of its children use the named session variable.
	 * Callers can use this to determine if a message needs to be preserved.
	 */
	default boolean consumesSessionVariable(String sessionKey) {
		return false;
	}
}
