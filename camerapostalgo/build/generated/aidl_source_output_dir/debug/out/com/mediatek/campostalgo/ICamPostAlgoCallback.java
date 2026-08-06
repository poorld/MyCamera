/*
 * This file is auto-generated.  DO NOT MODIFY.
 * Using: C:\Users\teeny\AppData\Local\Android\Sdk\build-tools\35.0.0\aidl.exe -pC:\Users\teeny\AppData\Local\Android\Sdk\platforms\android-35\framework.aidl -oD:\hongda\app\MyCamera\camerapostalgo\build\generated\aidl_source_output_dir\debug\out -ID:\hongda\app\MyCamera\camerapostalgo\src\main\aidl -ID:\hongda\app\MyCamera\camerapostalgo\src\debug\aidl -dC:\Users\teeny\AppData\Local\Temp\aidl7076256622340858132.d D:\hongda\app\MyCamera\camerapostalgo\src\main\aidl\com\mediatek\campostalgo\ICamPostAlgoCallback.aidl
 */
package com.mediatek.campostalgo;
public interface ICamPostAlgoCallback extends android.os.IInterface
{
  /** Default implementation for ICamPostAlgoCallback. */
  public static class Default implements com.mediatek.campostalgo.ICamPostAlgoCallback
  {
    @Override public void processResult(com.mediatek.campostalgo.FeatureParam params) throws android.os.RemoteException
    {
    }
    @Override
    public android.os.IBinder asBinder() {
      return null;
    }
  }
  /** Local-side IPC implementation stub class. */
  public static abstract class Stub extends android.os.Binder implements com.mediatek.campostalgo.ICamPostAlgoCallback
  {
    /** Construct the stub at attach it to the interface. */
    @SuppressWarnings("this-escape")
    public Stub()
    {
      this.attachInterface(this, DESCRIPTOR);
    }
    /**
     * Cast an IBinder object into an com.mediatek.campostalgo.ICamPostAlgoCallback interface,
     * generating a proxy if needed.
     */
    public static com.mediatek.campostalgo.ICamPostAlgoCallback asInterface(android.os.IBinder obj)
    {
      if ((obj==null)) {
        return null;
      }
      android.os.IInterface iin = obj.queryLocalInterface(DESCRIPTOR);
      if (((iin!=null)&&(iin instanceof com.mediatek.campostalgo.ICamPostAlgoCallback))) {
        return ((com.mediatek.campostalgo.ICamPostAlgoCallback)iin);
      }
      return new com.mediatek.campostalgo.ICamPostAlgoCallback.Stub.Proxy(obj);
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
        case TRANSACTION_processResult:
        {
          com.mediatek.campostalgo.FeatureParam _arg0;
          _arg0 = _Parcel.readTypedObject(data, com.mediatek.campostalgo.FeatureParam.CREATOR);
          this.processResult(_arg0);
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
    private static class Proxy implements com.mediatek.campostalgo.ICamPostAlgoCallback
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
      @Override public void processResult(com.mediatek.campostalgo.FeatureParam params) throws android.os.RemoteException
      {
        android.os.Parcel _data = android.os.Parcel.obtain();
        android.os.Parcel _reply = android.os.Parcel.obtain();
        try {
          _data.writeInterfaceToken(DESCRIPTOR);
          _Parcel.writeTypedObject(_data, params, 0);
          boolean _status = mRemote.transact(Stub.TRANSACTION_processResult, _data, _reply, 0);
          _reply.readException();
        }
        finally {
          _reply.recycle();
          _data.recycle();
        }
      }
    }
    static final int TRANSACTION_processResult = (android.os.IBinder.FIRST_CALL_TRANSACTION + 0);
  }
  /** @hide */
  public static final java.lang.String DESCRIPTOR = "com.mediatek.campostalgo.ICamPostAlgoCallback";
  public void processResult(com.mediatek.campostalgo.FeatureParam params) throws android.os.RemoteException;
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
