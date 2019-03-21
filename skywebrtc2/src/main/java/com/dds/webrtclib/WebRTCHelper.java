package com.dds.webrtclib;


import android.content.Context;
import android.media.AudioManager;
import android.os.Parcelable;
import android.util.Log;

import com.dds.webrtclib.ws.ISignalingEvents;
import com.dds.webrtclib.ws.IWebSocket;
import com.dds.webrtclib.ws.JavaWebSocket;

import org.webrtc.AudioSource;
import org.webrtc.AudioTrack;
import org.webrtc.Camera1Enumerator;
import org.webrtc.Camera2Enumerator;
import org.webrtc.CameraEnumerator;
import org.webrtc.CameraVideoCapturer;
import org.webrtc.DataChannel;
import org.webrtc.EglBase;
import org.webrtc.IceCandidate;
import org.webrtc.MediaConstraints;
import org.webrtc.MediaStream;
import org.webrtc.PeerConnection;
import org.webrtc.PeerConnectionFactory;
import org.webrtc.SdpObserver;
import org.webrtc.SessionDescription;
import org.webrtc.VideoCapturer;
import org.webrtc.VideoSource;
import org.webrtc.VideoTrack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;


public class WebRTCHelper implements ISignalingEvents {

    public final static String TAG = "dds_webrtc";

    public static final int VIDEO_RESOLUTION_WIDTH = 1280;
    public static final int VIDEO_RESOLUTION_HEIGHT = 720;
    public static final int FPS = 30;

    public static final String VIDEO_TRACK_ID = "ARDAMSv0";
    public static final String AUDIO_TRACK_ID = "ARDAMSa0";

    private PeerConnectionFactory _factory;
    private MediaStream _localStream;
    private AudioTrack _localAudioTrack;
    private VideoCapturer captureAndroid;
    private VideoSource videoSource;

    private AudioManager mAudioManager;


    private ArrayList<String> _connectionIdArray;
    private Map<String, Peer> _connectionPeerDic;

    private String _myId;
    private IWebRTCHelper IHelper;

    private ArrayList<PeerConnection.IceServer> ICEServers;
    private boolean videoEnable;

    enum Role {Caller, Receiver,}

    private Role _role;

    private IWebSocket webSocket;

    private Context _context;
    private EglBase.Context _eglContext;

    public WebRTCHelper(Context context, IWebRTCHelper IHelper, Parcelable[] servers, EglBase.Context eglContext) {
        this.IHelper = IHelper;
        _context = context;
        _eglContext = eglContext;
        this._connectionPeerDic = new HashMap<>();
        this._connectionIdArray = new ArrayList<>();
        this.ICEServers = new ArrayList<>();
        for (int i = 0; i < servers.length; i++) {
            MyIceServer myIceServer = (MyIceServer) servers[i];
            PeerConnection.IceServer iceServer = new PeerConnection.IceServer(myIceServer.uri, myIceServer.username, myIceServer.password);
            ICEServers.add(iceServer);
        }
        webSocket = new JavaWebSocket(this);
        mAudioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
    }

    public void initSocket(String ws, final String room, boolean videoEnable) {
        this.videoEnable = videoEnable;
        webSocket.connect(ws, room);
    }


    // ===================================webSocket回调信息=======================================
    @Override  // 我加入到房间
    public void onJoinToRoom(ArrayList<String> connections, String myId) {
        _connectionIdArray.addAll(connections);
        _myId = myId;
        if (_factory == null) {
            PeerConnectionFactory.initializeAndroidGlobals(_context, true, true, true);
            _factory = new PeerConnectionFactory(null);
            _factory.setVideoHwAccelerationOptions(_eglContext,_eglContext);

        }
        if (_localStream == null) {
            createLocalStream();
        }
        createPeerConnections();
        addStreams();
        createOffers();
    }

    @Override  // 其他人加入到房间
    public void onRemoteJoinToRoom(String socketId) {
        if (_localStream == null) {
            createLocalStream();
        }
        Peer mPeer = new Peer(socketId);
        mPeer.pc.addStream(_localStream);

        _connectionIdArray.add(socketId);
        _connectionPeerDic.put(socketId, mPeer);
    }

    @Override
    public void onRemoteIceCandidate(String socketId, IceCandidate iceCandidate) {
        Peer peer = _connectionPeerDic.get(socketId);
        if (peer != null) {
            peer.pc.addIceCandidate(iceCandidate);
        }
    }

    @Override
    public void onRemoteOutRoom(String socketId) {
        closePeerConnection(socketId);
    }

    @Override
    public void onReceiveOffer(String socketId, String sdp) {
        _role = Role.Receiver;
        Peer mPeer = _connectionPeerDic.get(socketId);
        SessionDescription sessionDescription = new SessionDescription(SessionDescription.Type.OFFER, sdp);
        mPeer.pc.setRemoteDescription(mPeer, sessionDescription);
    }

    @Override
    public void onReceiverAnswer(String socketId, String sdp) {
        Peer mPeer = _connectionPeerDic.get(socketId);
        SessionDescription sessionDescription = new SessionDescription(SessionDescription.Type.ANSWER, sdp);
        mPeer.pc.setRemoteDescription(mPeer, sessionDescription);
    }


    //**************************************逻辑控制**************************************
    // 调整摄像头前置后置
    public void switchCamera() {
        if (captureAndroid == null) return;
        if (captureAndroid instanceof CameraVideoCapturer) {
            CameraVideoCapturer cameraVideoCapturer = (CameraVideoCapturer) captureAndroid;
            cameraVideoCapturer.switchCamera(new CameraVideoCapturer.CameraSwitchHandler() {
                @Override
                public void onCameraSwitchDone(boolean b) {
                    Log.d("dds", "onCameraSwitchDone");
                }

                @Override
                public void onCameraSwitchError(String s) {
                    Log.d("dds", "onCameraSwitchError");
                }
            });
        } else {
            Log.d(TAG, "Will not switch camera, video caputurer is not a camera");
        }

    }

    // 设置自己静音
    public void toggleMute(boolean enable) {
        if (_localAudioTrack != null) {
            _localAudioTrack.setEnabled(enable);
        }
    }

    public void toggleSpeaker(boolean enable) {
        if (mAudioManager != null) {
            mAudioManager.setSpeakerphoneOn(enable);
        }

    }

    // 退出房间
    public void exitRoom() {
        ArrayList myCopy;
        myCopy = (ArrayList) _connectionIdArray.clone();
        for (Object Id : myCopy) {
            closePeerConnection((String) Id);
        }
        webSocket.close();
        if (_connectionIdArray != null) {
            _connectionIdArray.clear();
        }
        _localStream = null;

    }

    // 创建本地流
    private void createLocalStream() {
        _localStream = _factory.createLocalMediaStream("ARDAMS");
        // 音频
        AudioSource audioSource = _factory.createAudioSource(new MediaConstraints());
        _localAudioTrack = _factory.createAudioTrack(AUDIO_TRACK_ID, audioSource);
        _localStream.addTrack(_localAudioTrack);

        if (videoEnable) {
            //创建需要传入设备的名称
            captureAndroid = createVideoCapture();
            // 视频
            videoSource = _factory.createVideoSource(captureAndroid);
            captureAndroid.startCapture(VIDEO_RESOLUTION_WIDTH, VIDEO_RESOLUTION_HEIGHT, FPS);

            VideoTrack localVideoTrack = _factory.createVideoTrack(VIDEO_TRACK_ID, videoSource);
            _localStream.addTrack(localVideoTrack);
        }


        if (IHelper != null) {
            IHelper.onSetLocalStream(_localStream, _myId);
        }

    }

    // 创建所有连接
    private void createPeerConnections() {
        for (Object str : _connectionIdArray) {
            Peer peer = new Peer((String) str);
            _connectionPeerDic.put((String) str, peer);
        }
    }

    // 为所有连接添加流
    private void addStreams() {
        Log.v(TAG, "为所有连接添加流");
        for (Map.Entry<String, Peer> entry : _connectionPeerDic.entrySet()) {
            if (_localStream == null) {
                createLocalStream();
            }
            entry.getValue().pc.addStream(_localStream);
        }

    }

    // 为所有连接创建offer
    private void createOffers() {
        Log.v(TAG, "为所有连接创建offer");

        for (Map.Entry<String, Peer> entry : _connectionPeerDic.entrySet()) {
            _role = Role.Caller;
            Peer mPeer = entry.getValue();
            mPeer.pc.createOffer(mPeer, offerOrAnswerConstraint());
        }

    }


    // 关闭通道流
    private void closePeerConnection(String connectionId) {
        Log.v(TAG, "关闭通道流");
        Peer mPeer = _connectionPeerDic.get(connectionId);
        if (mPeer != null) {
            mPeer.pc.close();
        }
        _connectionPeerDic.remove(connectionId);
        _connectionIdArray.remove(connectionId);

        IHelper.onCloseWithId(connectionId);
    }


    private VideoCapturer createVideoCapture() {
        VideoCapturer videoCapturer;
        if (useCamera2()) {
            videoCapturer = createCameraCapture(new Camera2Enumerator(_context));
        } else {
            videoCapturer = createCameraCapture(new Camera1Enumerator(true));
        }
        return videoCapturer;
    }

    private VideoCapturer createCameraCapture(CameraEnumerator enumerator) {
        final String[] deviceNames = enumerator.getDeviceNames();

        // First, try to find front facing camera
        for (String deviceName : deviceNames) {
            if (enumerator.isFrontFacing(deviceName)) {
                VideoCapturer videoCapturer = enumerator.createCapturer(deviceName, null);

                if (videoCapturer != null) {
                    return videoCapturer;
                }
            }
        }

        // Front facing camera not found, try something else
        for (String deviceName : deviceNames) {
            if (!enumerator.isFrontFacing(deviceName)) {
                VideoCapturer videoCapturer = enumerator.createCapturer(deviceName, null);

                if (videoCapturer != null) {
                    return videoCapturer;
                }
            }
        }

        return null;
    }

    private boolean useCamera2() {
        return Camera2Enumerator.isSupported(_context);
    }


    //**************************************各种约束******************************************/
    private MediaConstraints localVideoConstraints() {
        MediaConstraints mediaConstraints = new MediaConstraints();
        ArrayList<MediaConstraints.KeyValuePair> keyValuePairs = new ArrayList<>();
        keyValuePairs.add(new MediaConstraints.KeyValuePair("maxWidth", "360"));
        keyValuePairs.add(new MediaConstraints.KeyValuePair("minWidth", "160"));
        keyValuePairs.add(new MediaConstraints.KeyValuePair("maxHeight", "640"));
        keyValuePairs.add(new MediaConstraints.KeyValuePair("minHeight", "120"));
        keyValuePairs.add(new MediaConstraints.KeyValuePair("minFrameRate", "1"));
        keyValuePairs.add(new MediaConstraints.KeyValuePair("maxFrameRate", "5"));
        mediaConstraints.mandatory.addAll(keyValuePairs);
        return mediaConstraints;
    }

    private MediaConstraints peerConnectionConstraints() {
        MediaConstraints mediaConstraints = new MediaConstraints();
        ArrayList<MediaConstraints.KeyValuePair> keyValuePairs = new ArrayList<>();
        keyValuePairs.add(new MediaConstraints.KeyValuePair("DtlsSrtpKeyAgreement", "true"));
        keyValuePairs.add(new MediaConstraints.KeyValuePair("minFrameRate", "1"));
        keyValuePairs.add(new MediaConstraints.KeyValuePair("maxFrameRate", "5"));

        mediaConstraints.optional.addAll(keyValuePairs);
        return mediaConstraints;
    }

    private MediaConstraints offerOrAnswerConstraint() {
        MediaConstraints mediaConstraints = new MediaConstraints();
        ArrayList<MediaConstraints.KeyValuePair> keyValuePairs = new ArrayList<>();
        keyValuePairs.add(new MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"));
        keyValuePairs.add(new MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"));
        mediaConstraints.mandatory.addAll(keyValuePairs);
        return mediaConstraints;
    }


    //**************************************内部类******************************************/
    private class Peer implements SdpObserver, PeerConnection.Observer {
        private PeerConnection pc;
        private String socketId;

        public Peer(String socketId) {
            this.pc = createPeerConnection();
            this.socketId = socketId;

        }


        //****************************PeerConnection.Observer****************************/
        @Override
        public void onSignalingChange(PeerConnection.SignalingState signalingState) {
            Log.v(TAG, "ice 状态改变 " + signalingState);
        }

        @Override
        public void onIceConnectionChange(PeerConnection.IceConnectionState iceConnectionState) {
        }

        @Override
        public void onIceConnectionReceivingChange(boolean b) {

        }

        @Override
        public void onIceGatheringChange(PeerConnection.IceGatheringState iceGatheringState) {
        }


        @Override
        public void onIceCandidate(IceCandidate iceCandidate) {
            // 发送IceCandidate
            webSocket.sendIceCandidate(socketId, iceCandidate);
        }

        @Override
        public void onIceCandidatesRemoved(IceCandidate[] iceCandidates) {

        }

        @Override
        public void onAddStream(MediaStream mediaStream) {
            if (IHelper != null) {
                IHelper.onAddRemoteStream(mediaStream, socketId);
            }
        }

        @Override
        public void onRemoveStream(MediaStream mediaStream) {
            if (IHelper != null) {
                IHelper.onCloseWithId(socketId);
            }
        }

        @Override
        public void onDataChannel(DataChannel dataChannel) {

        }

        @Override
        public void onRenegotiationNeeded() {

        }


        //****************************SdpObserver****************************/

        @Override
        public void onCreateSuccess(SessionDescription sessionDescription) {
            Log.v(TAG, "sdp创建成功       " + sessionDescription.type);
            //设置本地的SDP
            pc.setLocalDescription(Peer.this, sessionDescription);
        }

        @Override
        public void onSetSuccess() {
            Log.v(TAG, "sdp连接成功        " + pc.signalingState().toString());

            if (pc.signalingState() == PeerConnection.SignalingState.HAVE_REMOTE_OFFER) {
                pc.createAnswer(Peer.this, offerOrAnswerConstraint());
            } else if (pc.signalingState() == PeerConnection.SignalingState.HAVE_LOCAL_OFFER) {
                //判断连接状态为本地发送offer
                if (_role == Role.Receiver) {
                    //接收者，发送Answer
                    webSocket.sendAnswer(socketId, pc.getLocalDescription().description);

                } else if (_role == Role.Caller) {
                    //发送者,发送自己的offer
                    webSocket.sendOffer(socketId, pc.getLocalDescription().description);
                }

            } else if (pc.signalingState() == PeerConnection.SignalingState.STABLE) {
                // Stable 稳定的
                if (_role == Role.Receiver) {
                    webSocket.sendAnswer(socketId, pc.getLocalDescription().description);

                }
            }

        }

        @Override
        public void onCreateFailure(String s) {

        }

        @Override
        public void onSetFailure(String s) {

        }


        //初始化 RTCPeerConnection 连接管道
        private PeerConnection createPeerConnection() {

            if (_factory == null) {
                PeerConnectionFactory.initializeAndroidGlobals(IHelper, true, true, true);
                _factory = new PeerConnectionFactory();
            }
            // 管道连接抽象类实现方法
            return _factory.createPeerConnection(ICEServers, peerConnectionConstraints(), this);
        }

    }


}



