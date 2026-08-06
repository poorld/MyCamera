/*
 * This file is auto-generated.  DO NOT MODIFY.
 * Using: C:\Users\teeny\AppData\Local\Android\Sdk\build-tools\35.0.0\aidl.exe -pC:\Users\teeny\AppData\Local\Android\Sdk\platforms\android-35\framework.aidl -oD:\hongda\app\MyCamera\camerapostalgo\build\generated\aidl_source_output_dir\debug\out -ID:\hongda\app\MyCamera\camerapostalgo\src\main\aidl -ID:\hongda\app\MyCamera\camerapostalgo\src\debug\aidl -dC:\Users\teeny\AppData\Local\Temp\aidl772495363839241156.d D:\hongda\app\MyCamera\camerapostalgo\src\main\aidl\com\mediatek\campostalgo\ICamPostAlgoService.aidl
 */
package com.mediatek.campostalgo;
/** Binder interface for the native camera post algo service. */
public interface ICamPostAlgoService extends android.os.IInterface
{
  /** Default implementation for ICamPostAlgoService. */
  public static class Default implements com.mediatek.campostalgo.ICamPostAlgoService
  {
    /** open a camera post algo processor */
    @Override public com.mediatek.campostalgo.ICamPostAlgoInterface connect(java.lang.String opPackageName, int clientUid) throws android.os.RemoteException
    {
      return null;
    }
    @Override
    public android.os.IBinder asBinder() {
      return null;
    }
  }
  /** Local-side IPC implementation stub class. */
  public static abstract class Stub extends android.os.Binder implements com.mediatek.campostalgo.ICamPostAlgoService
  {
    /** Construct the stub at attach it to the interface. */
    @SuppressWarnings("this-escape")
    public Stub()
    {
      this.attachInterface(this, DESCRIPTOR);
    }
    /**
     * Cast an IBinder object into an com.mediatek.campostalgo.ICamPostAlgoService interface,
     * generating a proxy if needed.
     */
    public static com.mediatek.campostalgo.ICamPostAlgoService asInterface(android.os.IBinder obj)
    {
      if ((obj==null)) {
        return null;
      }
      android.os.IInterface iin = obj.queryLocalInterface(DESCRIPTOR);
      if (((iin!=null)&&(iin instanceof com.mediatek.campostalgo.ICamPostAlgoService))) {
        return ((com.mediatek.campostalgo.ICamPostAlgoService)iin);
      }
      return new com.mediatek.campostalgo.ICamPostAlgoService.Stub.Proxy(obj);
    }
    @Override public android.os.IBinder asBinder()
    {
      return this;
    }
    @Override public boolean onTransact(int code, android.os.Parcel data, android.os.Parcel reply, int flags) throws android.os.RemoteException
    {
      java.lang.String descriptor = DESCRIPTOR;
      if (code >= android.os.IBinder.FIRST_CALL_TRANSACTION && code <= android.os.IBinder.LAST_CALL_TRANSACTION) {
        data.enforceInterface(descriptor);
      }
      if (code == INTERFACE_TRANSACTION) {
        reply.writeString(descriptor);
        return true;
      }
      switch (code)
      {
        case TRANSACTION_connect:
        {
          java.lang.String _arg0;
          _arg0 = data.readString();
          int _arg1;
          _arg1 = data.readInt();
          com.mediatek.campostalgo.ICamPostAlgoInterface _result = this.connect(_arg0, _arg1);
          reply.writeNoException();
          reply.writeStrongInterface(_result);
          break;
        }
        default:
        {
          return super.onTransact(code, data, reply, flags);
        }
      }
      return true;
    }
    private static class Proxy implements com.mediatek.campostalgo.ICamPostAlgoService
    {
      private android.os.IBinder mRemote;
      Proxy(android.os.IBinder remote)
      {
        mRemote = remote;
      }
      @Override public android.os.IBinder asBinder()
      {
        return mRemote;
      }
      public java.lang.String getInterfaceDescriptor()
      {
        return DESCRIPTOR;
      }
      /** open a camera post algo processor */
      @Override public com.mediatek.campostalgo.ICamPostAlgoInterface connect(java.lang.String opPackageName, int clientUid) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        com.mediatek.campostalgo.ICamPostAlgoInterface _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeString(opPackageName);
          _data.writeInt(clientUid);
          boolean _status = mRemote.transact(Stub.TRANSACTION_connect, _data, _reply, 0);
          _reply.readException();
          _result = com.mediatek.campostalgo.ICamPostAlgoInterface.Stub.asInterface(_reply.readStrongBinder());
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
    }
    static final int TRANSACTION_connect = (android.os.IBinder.FIRST_CALL_TRANSACTION + 0);
  }
  /** @hide */
  public static final java.lang.String DESCRIPTOR = "com.mediatek.campostalgo.ICamPostAlgoService";
  /**
   * All camera postalgo service and Binder calls may return a
   * ServiceSpecificException with the following error codes
   */
  public static final int ERROR_PERMISSION_DENIED = 1;
  public static final int ERROR_ILLEGAL_ARGUMENT = 2;
  public static final int ERROR_TIMED_OUT = 3;
  public static final int ERROR_DISABLED = 4;
  public static final int ERROR_INVALID_OPERATION = 5;
  /**
   * Default UID/PID values for non-privileged callers of
   * connect()
   */
  public static final int USE_CALLING_UID = -1;
  public static final int USE_CALLING_PID = -1;
  /** open a camera post algo processor */
  public com.mediatek.campostalgo.ICamPostAlgoInterface connect(java.lang.String opPackageName, int clientUid) throws android.os.RemoteException;
}
