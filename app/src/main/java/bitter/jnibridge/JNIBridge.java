package bitter.jnibridge; import java.lang.reflect.Method; public class JNIBridge { private static native Object invoke(long handle, Class<?> cls, Method method, Object[] args); }
