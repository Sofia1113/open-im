import type {
  CallRoom,
  ServerToClientEvents,
} from "@open-im/contracts";
import { useCallback, useEffect, useRef, useState } from "react";
import type { RealtimeClient } from "./realtime";

// ============================================================
// WebRTC 通话 Hook
// 管理本地/远程媒体流、RTCPeerConnection 和信令协商。
// ============================================================

/** useRtcCall 输入参数 */
interface UseRtcCallInput {
  socket: RealtimeClient | null;
  call: CallRoom | null;
  userId: string;
}

/** 远程用户的媒体流 */
interface RemoteMedia {
  userId: string;
  stream: MediaStream;
}

/**
 * WebRTC 通话核心逻辑
 * - 获取本地摄像头/麦克风
 * - 管理与每个远程用户的 RTCPeerConnection
 * - 通过实时信令通道交换 SDP 和 ICE Candidate
 * - 提供静音、开关摄像头控制
 */
export function useRtcCall({ socket, call, userId }: UseRtcCallInput) {
  const [localStream, setLocalStream] = useState<MediaStream | null>(null);
  const [remoteMedia, setRemoteMedia] = useState<RemoteMedia[]>([]);
  const [mediaError, setMediaError] = useState<string | null>(null);
  const [micEnabled, setMicEnabled] = useState(true);
  const [cameraEnabled, setCameraEnabled] = useState(call?.mediaType === "video");
  const callRef = useRef(call);
  const streamRef = useRef<MediaStream | null>(null);
  const streamPromiseRef = useRef<Promise<MediaStream> | null>(null);
  const peersRef = useRef(new Map<string, RTCPeerConnection>());
  const candidatesRef = useRef(new Map<string, RTCIceCandidateInit[]>());
  const activatedRoomsRef = useRef(new Set<string>());

  callRef.current = call;

  /** 确保本地媒体流已获取，使用 Promise 缓存避免重复请求 */
  const ensureLocalStream = useCallback(async (): Promise<MediaStream> => {
    if (streamRef.current) return streamRef.current;
    if (streamPromiseRef.current) return streamPromiseRef.current;
    const activeCall = callRef.current;
    if (!activeCall) throw new Error("通话已结束");
    setMediaError(null);
    streamPromiseRef.current = navigator.mediaDevices
      .getUserMedia({ audio: true, video: activeCall.mediaType === "video" })
      .then((stream) => {
        streamRef.current = stream;
        setLocalStream(stream);
        setCameraEnabled(stream.getVideoTracks().some((track) => track.enabled));
        return stream;
      })
      .catch((error: unknown) => {
        setMediaError(
          error instanceof Error
            ? `无法使用摄像头或麦克风：${error.message}`
            : "无法使用摄像头或麦克风",
        );
        throw error;
      })
      .finally(() => {
        streamPromiseRef.current = null;
      });
    return streamPromiseRef.current;
  }, []);

  /**
   * 确保与指定远程用户的 PeerConnection 已建立
   * - 绑定 ICE candidate 和媒体轨道事件
   * - 使用 Google 公共 STUN 服务器
   */
  const ensurePeer = useCallback(
    async (remoteUserId: string): Promise<RTCPeerConnection> => {
      const existing = peersRef.current.get(remoteUserId);
      if (existing) return existing;
      if (!socket) throw new Error("实时连接尚未就绪");
      const activeCall = callRef.current;
      if (!activeCall) throw new Error("通话已结束");
      const stream = await ensureLocalStream();
      const peer = new RTCPeerConnection({
        iceServers: [{ urls: "stun:stun.l.google.com:19302" }],
      });
      peersRef.current.set(remoteUserId, peer);
      stream.getTracks().forEach((track) => peer.addTrack(track, stream));
      peer.onicecandidate = (event) => {
        if (!event.candidate || !callRef.current) return;
        socket.emit(
          "rtc:ice-candidate",
          {
            roomId: callRef.current.id,
            targetUserId: remoteUserId,
            candidate: event.candidate.toJSON(),
          },
          () => undefined,
        );
      };
      peer.ontrack = (event) => {
        const [streamFromEvent] = event.streams;
        const remoteStream = streamFromEvent ?? new MediaStream([event.track]);
        setRemoteMedia((current) => [
          ...current.filter((item) => item.userId !== remoteUserId),
          { userId: remoteUserId, stream: remoteStream },
        ]);
      };
      peer.onconnectionstatechange = () => {
        if (["failed", "closed"].includes(peer.connectionState)) {
          setRemoteMedia((current) =>
            current.filter((item) => item.userId !== remoteUserId),
          );
        }
      };
      return peer;
    },
    [ensureLocalStream, socket],
  );

  /** 监听来自服务端的 WebRTC 信令事件 */
  useEffect(() => {
    if (!socket) return;

    const onOffer: ServerToClientEvents["rtc:offer"] = async (payload) => {
      const activeCall = callRef.current;
      if (!activeCall || payload.roomId !== activeCall.id || !payload.sdp) return;
      try {
        const peer = await ensurePeer(payload.fromUserId);
        await peer.setRemoteDescription(payload.sdp);
        for (const candidate of candidatesRef.current.get(payload.fromUserId) ?? []) {
          await peer.addIceCandidate(candidate);
        }
        candidatesRef.current.delete(payload.fromUserId);
        const answer = await peer.createAnswer();
        await peer.setLocalDescription(answer);
        socket.emit(
          "rtc:answer",
          {
            roomId: activeCall.id,
            targetUserId: payload.fromUserId,
            sdp: answer,
          },
          () => undefined,
        );
      } catch (error) {
        setMediaError(error instanceof Error ? error.message : "通话协商失败");
      }
    };

    const onAnswer: ServerToClientEvents["rtc:answer"] = async (payload) => {
      const activeCall = callRef.current;
      if (!activeCall || payload.roomId !== activeCall.id || !payload.sdp) return;
      const peer = peersRef.current.get(payload.fromUserId);
      if (!peer) return;
      try {
        await peer.setRemoteDescription(payload.sdp);
        for (const candidate of candidatesRef.current.get(payload.fromUserId) ?? []) {
          await peer.addIceCandidate(candidate);
        }
        candidatesRef.current.delete(payload.fromUserId);
      } catch (error) {
        setMediaError(error instanceof Error ? error.message : "通话协商失败");
      }
    };

    const onCandidate: ServerToClientEvents["rtc:ice-candidate"] = async (
      payload,
    ) => {
      const activeCall = callRef.current;
      if (!activeCall || payload.roomId !== activeCall.id || !payload.candidate) return;
      const peer = peersRef.current.get(payload.fromUserId);
      if (!peer?.remoteDescription) {
        const pending = candidatesRef.current.get(payload.fromUserId) ?? [];
        pending.push(payload.candidate);
        candidatesRef.current.set(payload.fromUserId, pending);
        return;
      }
      try {
        await peer.addIceCandidate(payload.candidate);
      } catch (error) {
        setMediaError(error instanceof Error ? error.message : "网络候选处理失败");
      }
    };

    socket.on("rtc:offer", onOffer);
    socket.on("rtc:answer", onAnswer);
    socket.on("rtc:ice-candidate", onCandidate);
    return () => {
      socket.off("rtc:offer", onOffer);
      socket.off("rtc:answer", onAnswer);
      socket.off("rtc:ice-candidate", onCandidate);
    };
  }, [ensurePeer, socket]);

  /**
   * 通话激活时的初始协商
   * 如果当前用户是后来加入者，向所有已加入用户发送 Offer
   */
  useEffect(() => {
    if (!call || !socket) return;
    void ensureLocalStream().catch(() => undefined);
    if (activatedRoomsRef.current.has(call.id)) return;
    activatedRoomsRef.current.add(call.id);

    if (call.status === "active" && call.creatorId !== userId) {
      const existingParticipants = call.participants.filter(
        (participant) =>
          participant.userId !== userId && participant.state === "joined",
      );
      void Promise.all(
        existingParticipants.map(async (participant) => {
          const peer = await ensurePeer(participant.userId);
          const offer = await peer.createOffer();
          await peer.setLocalDescription(offer);
          socket.emit(
            "rtc:offer",
            {
              roomId: call.id,
              targetUserId: participant.userId,
              sdp: offer,
            },
            () => undefined,
          );
        }),
      ).catch((error: unknown) => {
        setMediaError(error instanceof Error ? error.message : "通话协商失败");
      });
    }
  }, [call, ensureLocalStream, ensurePeer, socket, userId]);

  /** 通话结束时清理所有 PeerConnection 和本地媒体流 */
  useEffect(() => {
    if (call) return;
    peersRef.current.forEach((peer) => peer.close());
    peersRef.current.clear();
    candidatesRef.current.clear();
    streamRef.current?.getTracks().forEach((track) => track.stop());
    streamRef.current = null;
    setLocalStream(null);
    setRemoteMedia([]);
    setMediaError(null);
  }, [call]);

  /** 切换麦克风静音/取消静音 */
  const toggleMic = () => {
    const next = !micEnabled;
    streamRef.current?.getAudioTracks().forEach((track) => {
      track.enabled = next;
    });
    setMicEnabled(next);
  };

  /** 切换摄像头开关 */
  const toggleCamera = () => {
    const next = !cameraEnabled;
    streamRef.current?.getVideoTracks().forEach((track) => {
      track.enabled = next;
    });
    setCameraEnabled(next);
  };

  return {
    localStream,
    remoteMedia,
    mediaError,
    micEnabled,
    cameraEnabled,
    toggleMic,
    toggleCamera,
  };
}
