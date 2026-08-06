/*
 * This file is auto-generated.  DO NOT MODIFY.
 * Using: C:\Users\teeny\AppData\Local\Android\Sdk\build-tools\35.0.0\aidl.exe -pC:\Users\teeny\AppData\Local\Android\Sdk\platforms\android-35\framework.aidl -oD:\hongda\app\MyCamera\camerapostalgo\build\generated\aidl_source_output_dir\debug\out -ID:\hongda\app\MyCamera\camerapostalgo\src\main\aidl -ID:\hongda\app\MyCamera\camerapostalgo\src\debug\aidl -dC:\Users\teeny\AppData\Local\Temp\aidl11580113058644222591.d D:\hongda\app\MyCamera\camerapostalgo\src\main\aidl\com\mediatek\campostalgo\ICamPostAlgoInterface.aidl
 */
package com.mediatek.campostalgo;
public interface ICamPostAlgoInterface extends android.os.IInterface
{
  /** Default implementation for ICamPostAlgoInterface. */
  public static class Default implements com.mediatek.campostalgo.ICamPostAlgoInterface
  {
    @Override public void disconnect() throws android.os.RemoteException
    {
    }
    @Override public int getStat() throws android.os.RemoteException
    {
      return 0;
    }
    @Override public long flush() throws android.os.RemoteException
    {
      return 0L;
    }
    @Override public com.mediatek.campostalgo.FeatureResult config(com.mediatek.campostalgo.FeatureConfig[] configs, com.mediatek.campostalgo.ICamPostAlgoCallback callback) throws android.os.RemoteException
    {
      return null;
    }
    @Override public void configParams(int type, com.mediatek.campostalgo.FeatureParam param) throws android.os.RemoteException
    {
    }
    @Override public void finalizeOutputConfig(com.mediatek.campostalgo.Stream[] streams) throws android.os.RemoteException
    {
    }
    @Override
    public android.os.IBinder asBinder() {
      return null;
    }
  }
  /** Local-side IPC implementation stub class. */
  public static abstract class Stub extends android.os.Binder implements com.mediatek.campostalgo.ICamPostAlgoInterface
  {
    /** Construct the stub at attach it to the interface. */
    @SuppressWarnings("this-escape")
    public Stub()
    {
      this.attachInterface(this, DESCRIPTOR);
    }
    /**
     * Cast an IBinder object into an com.mediatek.campostalgo.ICamPostAlgoInterface interface,
     * generating a proxy if needed.
     */
    public static com.mediatek.campostalgo.ICamPostAlgoInterface asInterface(android.os.IBinder obj)
    {
      if ((obj==null)) {
        return null;
      }
      android.os.IInterface iin = obj.queryLocalInterface(DESCRIPTOR);
      if (((iin!=null)&&(iin instanceof com.mediatek.campostalgo.ICamPostAlgoInterface))) {
        return ((com.mediatek.campostalgo.ICamPostAlgoInterface)iin);
      }
      return new com.mediatek.campostalgo.ICamPostAlgoInterface.Stub.Proxy(obj);
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
        case TRANSACTION_disconnect:
        {
          this.disconnect();
          reply.writeNoException();
          break;
        }
        case TRANSACTION_getStat:
        {
          int _result = this.getStat();
          reply.writeNoException();
          reply.writeInt(_result);
          break;
        }
        case TRANSACTION_flush:
        {
          long _result = this.flush();
          reply.writeNoException();
          reply.writeLong(_result);
          break;
        }
        case TRANSACTION_config:
        {
          com.mediatek.campostalgo.FeatureConfig[] _arg0;
          _arg0 = data.createTypedArray(com.mediatek.campostalgo.FeatureConfig.CREATOR);
          com.mediatek.campostalgo.ICamPostAlgoCallback _arg1;
          _arg1 = com.mediatek.campostalgo.ICamPostAlgoCallback.Stub.asInterface(data.readStrongBinder());
          com.mediatek.campostalgo.FeatureResult _result = this.config(_arg0, _arg1);
          reply.writeNoException();
          _Parcel.writeTypedObject(reply, _result, android.os.Parcelable.PARCELABLE_WRITE_RETURN_VALUE);
          break;
        }
        case TRANSACTION_configParams:
        {
          int _arg0;
          _arg0 = data.readInt();
          com.mediatek.campostalgo.FeatureParam _arg1;
          _arg1 = _Parcel.readTypedObject(data, com.mediatek.campostalgo.FeatureParam.CREATOR);
          this.configParams(_arg0, _arg1);
          reply.writeNoException();
          break;
        }
        case TRANSACTION_finalizeOutputConfig:
        {
          com.mediatek.campostalgo.Stream[] _arg0;
          _arg0 = data.createTypedArray(com.mediatek.campostalgo.Stream.CREATOR);
          this.finalizeOutputConfig(_arg0);
          reply.writeNoException();
          break;
        }
        default:
        {
          return super.onTransact(code, data, reply, flags);
        }
      }
      return true;
    }
    private static class Proxy implements com.mediatek.campostalgo.ICamPostAlgoInterface
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
      @Override public void disconnect() throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          boolean _status = mRemote.transact(Stub.TRANSACTION_disconnect, _data, _reply, 0);
          _reply.readException();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
      }
      @Override public int getStat() throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        int _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          boolean _status = mRemote.transact(Stub.TRANSACTION_getStat, _data, _reply, 0);
          _reply.readException();
          _result = _reply.readInt();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      @Override public long flush() throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        long _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          boolean _status = mRemote.transact(Stub.TRANSACTION_flush, _data, _reply, 0);
          _reply.readException();
          _result = _reply.readLong();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      @Override public com.mediatek.campostalgo.FeatureResult config(com.mediatek.campostalgo.FeatureConfig[] configs, com.mediatek.campostalgo.ICamPostAlgoCallback callback) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        com.mediatek.campostalgo.FeatureResult _result;
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeTypedArray(configs, 0);
          _data.writeStrongInterface(callback);
          boolean _status = mRemote.transact(Stub.TRANSACTION_config, _data, _reply, 0);
          _reply.readException();
          _result = _Parcel.readTypedObject(_reply, com.mediatek.campostalgo.FeatureResult.CREATOR);
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
        return _result;
      }
      @Override public void configParams(int type, com.mediatek.campostalgo.FeatureParam param) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeInt(type);
          _Parcel.writeTypedObject(_data, param, 0);
          boolean _status = mRemote.transact(Stub.TRANSACTION_configParams, _data, _reply, 0);
          _reply.readException();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
      }
      @Override public void finalizeOutputConfig(com.mediatek.campostalgo.Stream[] streams) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _data.writeTypedArray(streams, 0);
          boolean _status = mRemote.transact(Stub.TRANSACTION_finalizeOutputConfig, _data, _reply, 0);
          _reply.readException();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
      }
    }
    static final int TRANSACTION_disconnect = (android.os.IBinder.FIRST_CALL_TRANSACTION + 0);
    static final int TRANSACTION_getStat = (android.os.IBinder.FIRST_CALL_TRANSACTION + 1);
    static final int TRANSACTION_flush = (android.os.IBinder.FIRST_CALL_TRANSACTION + 2);
    static final int TRANSACTION_config = (android.os.IBinder.FIRST_CALL_TRANSACTION + 3);
    static final int TRANSACTION_configParams = (android.os.IBinder.FIRST_CALL_TRANSACTION + 4);
    static final int TRANSACTION_finalizeOutputConfig = (android.os.IBinder.FIRST_CALL_TRANSACTION + 5);
  }
  /** @hide */
  public static final java.lang.String DESCRIPTOR = "com.mediatek.campostalgo.ICamPostAlgoInterface";
  public static final int STAT_DESTROYED = -2;
  public static final int STAT_UNKNOWN = -1;
  public static final int STAT_INITED = 0;
  public static final int STAT_CONFIGURED = 1;
  public static final int STAT_DISCONNECTED = 2;
  public void disconnect() throws android.os.RemoteException;
  public int getStat() throws android.os.RemoteException;
  public long flush() throws android.os.RemoteException;
  public com.mediatek.campostalgo.FeatureResult config(com.mediatek.campostalgo.FeatureConfig[] configs, com.mediatek.campostalgo.ICamPostAlgoCallback callback) throws android.os.RemoteException;
  public void configParams(int type, com.mediatek.campostalgo.FeatureParam param) throws android.os.RemoteException;
  public void finalizeOutputConfig(com.mediatek.campostalgo.Stream[] streams) throws android.os.RemoteException;
  /** @hide */
  static class _Parcel {
    static private <T> T readTypedObject(
        android.os.Parcel parcel,
        android.os.Parcelable.Creator<T> c) {
      if (parcel.readInt() != 0) {
          return c.createFromParcel(parcel);
      } else {
          return null;
      }
    }
    static private <T extends android.os.Parcelable> void writeTypedObject(
        android.os.Parcel parcel, T value, int parcelableFlags) {
      if (value != null) {
        parcel.writeInt(1);
        value.writeToParcel(parcel, parcelableFlags);
      } else {
        parcel.writeInt(0);
      }
    }
  }
}
