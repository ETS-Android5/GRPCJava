package com.google.net.stubby.newtransport.okhttp;

import static com.google.common.base.Charsets.UTF_8;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Matchers.any;
import static org.mockito.Matchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.Service;
import com.google.common.util.concurrent.Service.State;
import com.google.common.util.concurrent.SettableFuture;
import com.google.net.stubby.Metadata;
import com.google.net.stubby.MethodDescriptor;
import com.google.net.stubby.Status;
import com.google.net.stubby.newtransport.AbstractStream;
import com.google.net.stubby.newtransport.ClientStreamListener;
import com.google.net.stubby.newtransport.okhttp.OkHttpClientTransport.ClientFrameHandler;
import com.google.net.stubby.newtransport.okhttp.OkHttpClientTransport.OkHttpClientStream;

import com.squareup.okhttp.internal.spdy.ErrorCode;
import com.squareup.okhttp.internal.spdy.FrameReader;

import okio.Buffer;

import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Tests for {@link OkHttpClientTransport}.
 */
@RunWith(JUnit4.class)
public class OkHttpClientTransportTest {
  private static final int TIME_OUT_MS = 5000000;
  private static final String NETWORK_ISSUE_MESSAGE = "network issue";
  // The gRPC header length, which includes 1 byte compression flag and 4 bytes message length.
  private static final int HEADER_LENGTH = 5;

  @Mock
  private AsyncFrameWriter frameWriter;
  @Mock
  MethodDescriptor<?, ?> method;
  private OkHttpClientTransport clientTransport;
  private MockFrameReader frameReader;
  private Map<Integer, OkHttpClientStream> streams;
  private ClientFrameHandler frameHandler;
  private ExecutorService executor;

  @Before
  public void setup() {
    AbstractStream.GRPC_V2_PROTOCOL = true;
    MockitoAnnotations.initMocks(this);
    streams = new HashMap<Integer, OkHttpClientStream>();
    frameReader = new MockFrameReader();
    executor = Executors.newCachedThreadPool();
    clientTransport = new OkHttpClientTransport(executor, frameReader, frameWriter, 3);
    clientTransport.startAsync();
    frameHandler = clientTransport.getHandler();
    streams = clientTransport.getStreams();
    when(method.getName()).thenReturn("fakemethod");
    when(frameWriter.maxDataLength()).thenReturn(Integer.MAX_VALUE);
  }

  @After
  public void tearDown() {
    State state = clientTransport.state();
    if (state == State.NEW || state == State.RUNNING) {
      clientTransport.stopAsync();
      assertTrue(frameReader.closed);
      verify(frameWriter).close();
    }
    executor.shutdown();
  }

  /**
   * When nextFrame throws IOException, the transport should be aborted.
   */
  @Test
  public void nextFrameThrowIOException() throws Exception {
    MockStreamListener listener1 = new MockStreamListener();
    MockStreamListener listener2 = new MockStreamListener();
    clientTransport.newStream(method, new Metadata.Headers(), listener1);
    clientTransport.newStream(method, new Metadata.Headers(), listener2);
    assertEquals(2, streams.size());
    assertTrue(streams.containsKey(3));
    assertTrue(streams.containsKey(5));
    frameReader.throwIOExceptionForNextFrame();
    listener1.waitUntilStreamClosed();
    listener2.waitUntilStreamClosed();
    assertEquals(0, streams.size());
    assertEquals(Status.INTERNAL.getCode(), listener1.status.getCode());
    assertEquals(NETWORK_ISSUE_MESSAGE, listener2.status.getCause().getMessage());
    assertEquals(Status.INTERNAL.getCode(), listener1.status.getCode());
    assertEquals(NETWORK_ISSUE_MESSAGE, listener2.status.getCause().getMessage());
    assertEquals(Service.State.FAILED, clientTransport.state());
  }

  @Test
  public void readMessages() throws Exception {
    final int numMessages = 10;
    final String message = "Hello Client";
    MockStreamListener listener = new MockStreamListener();
    clientTransport.newStream(method, new Metadata.Headers(), listener);
    assertTrue(streams.containsKey(3));
    for (int i = 0; i < numMessages; i++) {
      Buffer buffer = createMessageFrame(message + i);
      frameHandler.data(i == numMessages - 1 ? true : false, 3, buffer, (int) buffer.size());
    }
    listener.waitUntilStreamClosed();
    assertEquals(Status.OK, listener.status);
    assertEquals(numMessages, listener.messages.size());
    for (int i = 0; i < numMessages; i++) {
      assertEquals(message + i, listener.messages.get(i));
    }
  }

  @Ignore
  /**
   * TODO (simonma): Re-implement this test, since status is carried by header instead of data frame
   * in V2 protocol.
   */
  public void readStatus() throws Exception {
  }

  @Test
  public void receiveReset() throws Exception {
    MockStreamListener listener = new MockStreamListener();
    clientTransport.newStream(method,new Metadata.Headers(), listener);
    assertTrue(streams.containsKey(3));
    frameHandler.rstStream(3, ErrorCode.PROTOCOL_ERROR);
    listener.waitUntilStreamClosed();
    assertEquals(OkHttpClientTransport.toGrpcStatus(ErrorCode.PROTOCOL_ERROR), listener.status);
  }

  @Test
  public void cancelStream() throws Exception {
    MockStreamListener listener = new MockStreamListener();
    clientTransport.newStream(method,new Metadata.Headers(), listener);
    OkHttpClientStream stream = streams.get(3);
    assertNotNull(stream);
    stream.cancel();
    verify(frameWriter).rstStream(eq(3), eq(ErrorCode.CANCEL));
    listener.waitUntilStreamClosed();
    assertEquals(OkHttpClientTransport.toGrpcStatus(ErrorCode.CANCEL), listener.status);
  }

  @Test
  public void writeMessage() throws Exception {
    final String message = "Hello Server";
    MockStreamListener listener = new MockStreamListener();
    clientTransport.newStream(method,new Metadata.Headers(), listener);
    OkHttpClientStream stream = streams.get(3);
    InputStream input = new ByteArrayInputStream(message.getBytes(UTF_8));
    assertEquals(12, input.available());
    stream.writeMessage(input, input.available(), null);
    stream.flush();
    ArgumentCaptor<Buffer> captor = ArgumentCaptor.forClass(Buffer.class);
    verify(frameWriter).data(eq(false), eq(3), captor.capture(), eq(12 + HEADER_LENGTH));
    Buffer sentFrame = captor.getValue();
    assertEquals(createMessageFrame(message), sentFrame);
  }

  @Test
  public void windowUpdate() throws Exception {
    MockStreamListener listener1 = new MockStreamListener();
    MockStreamListener listener2 = new MockStreamListener();
    clientTransport.newStream(method,new Metadata.Headers(), listener1);
    clientTransport.newStream(method,new Metadata.Headers(), listener2);
    assertEquals(2, streams.size());
    OkHttpClientStream stream1 = streams.get(3);
    OkHttpClientStream stream2 = streams.get(5);

    int messageLength = OkHttpClientTransport.DEFAULT_INITIAL_WINDOW_SIZE / 4;
    byte[] fakeMessage = new byte[messageLength];

    // Stream 1 receives a message
    Buffer buffer = createMessageFrame(fakeMessage);
    int messageFrameLength = (int) buffer.size();
    frameHandler.data(false, 3, buffer, messageFrameLength);

    // Stream 2 receives a message
    buffer = createMessageFrame(fakeMessage);
    frameHandler.data(false, 5, buffer, messageFrameLength);

    verify(frameWriter).windowUpdate(eq(0), eq((long) 2 * messageFrameLength));
    reset(frameWriter);

    // Stream 1 receives another message
    buffer = createMessageFrame(fakeMessage);
    frameHandler.data(false, 3, buffer, messageFrameLength);

    verify(frameWriter).windowUpdate(eq(3), eq((long) 2 * messageFrameLength));

    // Stream 2 receives another message
    buffer = createMessageFrame(fakeMessage);
    frameHandler.data(false, 5, buffer, messageFrameLength);

    verify(frameWriter).windowUpdate(eq(5), eq((long) 2 * messageFrameLength));
    verify(frameWriter).windowUpdate(eq(0), eq((long) 2 * messageFrameLength));

    stream1.cancel();
    verify(frameWriter).rstStream(eq(3), eq(ErrorCode.CANCEL));
    listener1.waitUntilStreamClosed();
    assertEquals(OkHttpClientTransport.toGrpcStatus(ErrorCode.CANCEL), listener1.status);

    stream2.cancel();
    verify(frameWriter).rstStream(eq(5), eq(ErrorCode.CANCEL));
    listener2.waitUntilStreamClosed();
    assertEquals(OkHttpClientTransport.toGrpcStatus(ErrorCode.CANCEL), listener2.status);
  }

  @Test
  public void windowUpdateWithInboundFlowControl() throws Exception {
    SettableFuture<Void> future = SettableFuture.create();
    MockStreamListener listener = new MockStreamListener(future);
    clientTransport.newStream(method, new Metadata.Headers(), listener);
    OkHttpClientStream stream = streams.get(3);

    int messageLength = OkHttpClientTransport.DEFAULT_INITIAL_WINDOW_SIZE / 2 + 1;
    byte[] fakeMessage = new byte[messageLength];

    Buffer buffer = createMessageFrame(fakeMessage);
    long messageFrameLength = buffer.size();
    frameHandler.data(false, 3, buffer, (int) messageFrameLength);
    verify(frameWriter).windowUpdate(eq(0), eq(messageFrameLength));
    verify(frameWriter, times(0)).windowUpdate(eq(3), eq(messageFrameLength));

    future.set(null);
    verify(frameWriter).windowUpdate(eq(3), eq(messageFrameLength));

    stream.cancel();
    verify(frameWriter).rstStream(eq(3), eq(ErrorCode.CANCEL));
    listener.waitUntilStreamClosed();
    assertEquals(OkHttpClientTransport.toGrpcStatus(ErrorCode.CANCEL), listener.status);
  }

  @Test
  public void stopNormally() throws Exception {
    MockStreamListener listener1 = new MockStreamListener();
    MockStreamListener listener2 = new MockStreamListener();
    clientTransport.newStream(method,new Metadata.Headers(), listener1);
    clientTransport.newStream(method,new Metadata.Headers(), listener2);
    assertEquals(2, streams.size());
    clientTransport.stopAsync();
    listener1.waitUntilStreamClosed();
    listener2.waitUntilStreamClosed();
    verify(frameWriter).goAway(eq(0), eq(ErrorCode.NO_ERROR), (byte[]) any());
    assertEquals(0, streams.size());
    assertEquals(Status.INTERNAL.getCode(), listener1.status.getCode());
    assertEquals(Status.INTERNAL.getCode(), listener2.status.getCode());
    assertEquals(Service.State.TERMINATED, clientTransport.state());
  }

  @Test
  public void receiveGoAway() throws Exception {
    // start 2 streams.
    MockStreamListener listener1 = new MockStreamListener();
    MockStreamListener listener2 = new MockStreamListener();
    clientTransport.newStream(method,new Metadata.Headers(), listener1);
    clientTransport.newStream(method,new Metadata.Headers(), listener2);
    assertEquals(2, streams.size());

    // Receive goAway, max good id is 3.
    frameHandler.goAway(3, ErrorCode.CANCEL, null);

    // Transport should be in STOPPING state.
    assertEquals(Service.State.STOPPING, clientTransport.state());

    // Stream 2 should be closed.
    listener2.waitUntilStreamClosed();
    assertEquals(1, streams.size());
    assertEquals(Status.UNAVAILABLE.getCode(), listener2.status.getCode());

    // New stream should be failed.
    MockStreamListener listener3 = new MockStreamListener();
    try {
      clientTransport.newStream(method,new Metadata.Headers(), listener3);
      fail("new stream should no be accepted by a go-away transport.");
    } catch (IllegalStateException ex) {
      // expected.
    }

    // But stream 1 should be able to send.
    final String sentMessage = "Should I also go away?";
    OkHttpClientStream stream = streams.get(3);
    InputStream input =
        new ByteArrayInputStream(sentMessage.getBytes(UTF_8));
    assertEquals(22, input.available());
    stream.writeMessage(input, input.available(), null);
    stream.flush();
    ArgumentCaptor<Buffer> captor =
        ArgumentCaptor.forClass(Buffer.class);
    verify(frameWriter).data(eq(false), eq(3), captor.capture(), eq(22 + HEADER_LENGTH));
    Buffer sentFrame = captor.getValue();
    assertEquals(createMessageFrame(sentMessage), sentFrame);

    // And read.
    final String receivedMessage = "No, you are fine.";
    Buffer buffer = createMessageFrame(receivedMessage);
    frameHandler.data(true, 3, buffer, (int) buffer.size());
    listener1.waitUntilStreamClosed();
    assertEquals(1, listener1.messages.size());
    assertEquals(receivedMessage, listener1.messages.get(0));

    // The transport should be stopped after all active streams finished.
    assertTrue("Service state: " + clientTransport.state(),
        Service.State.TERMINATED == clientTransport.state());
  }

  @Test
  public void streamIdExhaust() throws Exception {
    int startId = Integer.MAX_VALUE - 2;
    AsyncFrameWriter writer =  mock(AsyncFrameWriter.class);
    OkHttpClientTransport transport =
        new OkHttpClientTransport(executor, frameReader, writer, startId);
    transport.startAsync();
    streams = transport.getStreams();

    MockStreamListener listener1 = new MockStreamListener();
    transport.newStream(method,new Metadata.Headers(), listener1);

    try {
      transport.newStream(method, new Metadata.Headers(), new MockStreamListener());
      fail("new stream should not be accepted by a go-away transport.");
    } catch (IllegalStateException ex) {
      // expected.
    }

    streams.get(startId).cancel();
    listener1.waitUntilStreamClosed();
    verify(writer).rstStream(eq(startId), eq(ErrorCode.CANCEL));
    assertEquals(Service.State.TERMINATED, transport.state());
  }

  private static Buffer createMessageFrame(String message) {
    return createMessageFrame(message.getBytes(UTF_8));
  }

  private static Buffer createMessageFrame(byte[] message) {
    Buffer buffer = new Buffer();
    buffer.writeByte(0 /* UNCOMPRESSED */);
    buffer.writeInt(message.length);
    buffer.write(message);
    return buffer;
  }

  private static class MockFrameReader implements FrameReader {
    boolean closed;
    boolean throwExceptionForNextFrame;

    @Override
    public void close() throws IOException {
      closed = true;
    }

    @Override
    public boolean nextFrame(Handler handler) throws IOException {
      if (throwExceptionForNextFrame) {
        throw new IOException(NETWORK_ISSUE_MESSAGE);
      }
      synchronized (this) {
        try {
          wait();
        } catch (InterruptedException e) {
          throw new IOException(e);
        }
      }
      if (throwExceptionForNextFrame) {
        throw new IOException(NETWORK_ISSUE_MESSAGE);
      }
      return true;
    }

    synchronized void throwIOExceptionForNextFrame() {
      throwExceptionForNextFrame = true;
      notifyAll();
    }

    @Override
    public void readConnectionPreface() throws IOException {
      // not used.
    }
  }

  private static class MockStreamListener implements ClientStreamListener {
    Status status;
    CountDownLatch closed = new CountDownLatch(1);
    ArrayList<String> messages = new ArrayList<String>();
    final ListenableFuture<Void> messageFuture;

    MockStreamListener() {
      messageFuture = null;
    }

    MockStreamListener(ListenableFuture<Void> future) {
      messageFuture = future;
    }

    @Override
    public ListenableFuture<Void> headersRead(Metadata.Headers headers) {
      return null;
    }

    @Override
    public ListenableFuture<Void> messageRead(InputStream message, int length) {
      String msg = getContent(message);
      if (msg != null) {
        messages.add(msg);
      }
      return messageFuture;
    }

    @Override
    public void closed(Status status, Metadata.Trailers trailers) {
      this.status = status;
      closed.countDown();
    }

    void waitUntilStreamClosed() throws InterruptedException {
      if (!closed.await(TIME_OUT_MS, TimeUnit.MILLISECONDS)) {
        fail("Failed waiting stream to be closed.");
      }
    }

    static String getContent(InputStream message) {
      BufferedReader br =
          new BufferedReader(new InputStreamReader(message, UTF_8));
      try {
        // Only one line message is used in this test.
        return br.readLine();
      } catch (IOException e) {
        return null;
      }
    }
  }
}
