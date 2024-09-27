package com.nesp.android.clingdemo;

import android.Manifest;
import android.content.*;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AppCompatActivity;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.nesp.android.cling.Config;
import com.nesp.android.cling.Intents;
import com.nesp.android.cling.control.ClingPlayControl;
import com.nesp.android.cling.control.callback.ControlCallback;
import com.nesp.android.cling.control.callback.ControlReceiveCallback;
import com.nesp.android.cling.entity.*;
import com.nesp.android.cling.listener.BrowseRegistryListener;
import com.nesp.android.cling.listener.DeviceListChangedListener;
import com.nesp.android.cling.service.ClingUpnpService;
import com.nesp.android.cling.service.manager.ClingManager;
import com.nesp.android.cling.service.manager.DeviceManager;
import com.nesp.android.cling.util.Utils;
import org.fourthline.cling.model.meta.Device;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Collection;


import io.microshow.rxffmpeg.RxFFmpegInvoke;
import pub.devrel.easypermissions.EasyPermissions;


public class MainActivity extends AppCompatActivity implements SwipeRefreshLayout.OnRefreshListener, SeekBar.OnSeekBarChangeListener {

    private static final String TAG = MainActivity.class.getSimpleName();
    /** 连接设备状态: 播放状态 */
    public static final int PLAY_ACTION = 0xa1;
    /** 连接设备状态: 暂停状态 */
    public static final int PAUSE_ACTION = 0xa2;
    /** 连接设备状态: 停止状态 */
    public static final int STOP_ACTION = 0xa3;
    /** 连接设备状态: 转菊花状态 */
    public static final int TRANSITIONING_ACTION = 0xa4;
    /** 获取进度 */
    public static final int GET_POSITION_INFO_ACTION = 0xa5;
    /** 投放失败 */
    public static final int ERROR_ACTION = 0xa5;


    private static final String[] REQUESTED_PERMISSIONS = { Manifest.permission.WRITE_EXTERNAL_STORAGE, Manifest.permission.READ_EXTERNAL_STORAGE};

    private static final int PERMISSION_REQ_ID = 100;

    private String downloadPath;

    private Context mContext;
    private Handler mHandler = new InnerHandler();

    private Handler mainHandler = new Handler(Looper.getMainLooper());

    private int nHttpPort = 22120;
    private LocalHttpServer httpServer;


    private ListView mDeviceList;
    private SwipeRefreshLayout mRefreshLayout;
    private TextView mTVSelected;
    private SeekBar mSeekProgress;
    private SeekBar mSeekVolume;
    private Switch mSwitchMute;
    private Button mBtnDownload;
    private Button mBtnAddMark;
    private EditText mEdtMark;


    private BroadcastReceiver mTransportStateBroadcastReceiver;
    private ArrayAdapter<ClingDevice> mDevicesAdapter;
    /**
     * 投屏控制器
     */
    private ClingPlayControl mClingPlayControl = new ClingPlayControl();

    /** 用于监听发现设备 */
    private BrowseRegistryListener mBrowseRegistryListener = new BrowseRegistryListener();

    private ServiceConnection mUpnpServiceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName className, IBinder service) {
            Log.e(TAG, "mUpnpServiceConnection onServiceConnected");

            ClingUpnpService.LocalBinder binder = (ClingUpnpService.LocalBinder) service;
            ClingUpnpService beyondUpnpService = binder.getService();

            ClingManager clingUpnpServiceManager = ClingManager.getInstance();
            clingUpnpServiceManager.setUpnpService(beyondUpnpService);
            clingUpnpServiceManager.setDeviceManager(new DeviceManager());

            clingUpnpServiceManager.getRegistry().addListener(mBrowseRegistryListener);
            //Search on service created.
            clingUpnpServiceManager.searchDevices();
        }

        @Override
        public void onServiceDisconnected(ComponentName className) {
            Log.e(TAG, "mUpnpServiceConnection onServiceDisconnected");

            ClingManager.getInstance().setUpnpService(null);
        }
    };

    //    private ServiceConnection mSystemServiceConnection = new ServiceConnection() {
    //        @Override
    //        public void onServiceConnected(ComponentName className, IBinder service) {
    //            Log.e(TAG, "mSystemServiceConnection onServiceConnected");
    //
    //            SystemService.LocalBinder systemServiceBinder = (SystemService.LocalBinder) service;
    //            //Set binder to SystemManager
    //            ClingManager clingUpnpServiceManager = ClingManager.getInstance();
    ////            clingUpnpServiceManager.setSystemService(systemServiceBinder.getService());
    //        }
    //
    //        @Override
    //        public void onServiceDisconnected(ComponentName className) {
    //            Log.e(TAG, "mSystemServiceConnection onServiceDisconnected");
    //
    //            ClingUpnpServiceManager.getInstance().setSystemService(null);
    //        }
    //    };


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        mContext = this;

        initView();
        initListeners();
        bindServices();
        registerReceivers();

        initLocalVideo();
    }

    private void initLocalVideo() {
        RxFFmpegInvoke.getInstance().setDebug(true);
        downloadPath = Environment.getExternalStorageDirectory().getAbsolutePath() + File.separator + "Download" + File.separator + "qxcDownload";
        // 服务器端口
        int port = 22120;

        // 创建服务器实例
        httpServer = new LocalHttpServer(this, port, downloadPath);

        try {
            httpServer.start();
            Log.i(TAG,  String.format("http server port %d path %s url %s", port, downloadPath, getAddressPlayUrl(this)));
        } catch (IOException e) {
            e.printStackTrace();
            Log.e(TAG,  String.format("http server failed %s", e.toString()));
        }
    }

    private void registerReceivers() {
        //Register play status broadcast
        mTransportStateBroadcastReceiver = new TransportStateBroadcastReceiver();
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intents.ACTION_PLAYING);
        filter.addAction(Intents.ACTION_PAUSED_PLAYBACK);
        filter.addAction(Intents.ACTION_STOPPED);
        filter.addAction(Intents.ACTION_TRANSITIONING);
        registerReceiver(mTransportStateBroadcastReceiver, filter);
    }


    private void bindServices() {
        // Bind UPnP service
        Intent upnpServiceIntent = new Intent(MainActivity.this, ClingUpnpService.class);
        bindService(upnpServiceIntent, mUpnpServiceConnection, Context.BIND_AUTO_CREATE);
        // Bind System service
        //        Intent systemServiceIntent = new Intent(MainActivity.this, SystemService.class);
        //        bindService(systemServiceIntent, mSystemServiceConnection, Context.BIND_AUTO_CREATE);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mHandler.removeCallbacksAndMessages(null);
        // Unbind UPnP service
        unbindService(mUpnpServiceConnection);
        // Unbind System service
        //        unbindService(mSystemServiceConnection);
        // UnRegister Receiver
        unregisterReceiver(mTransportStateBroadcastReceiver);

        ClingManager.getInstance().destroy();
        ClingDeviceList.getInstance().destroy();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        // Forward results to EasyPermissions
        EasyPermissions.onRequestPermissionsResult(requestCode, permissions, grantResults, this);
    }

    private void initView() {
        mDeviceList = findViewById(R.id.lv_devices);
        mRefreshLayout = findViewById(R.id.srl_refresh);
        mTVSelected = findViewById(R.id.tv_selected);
        mSeekProgress = findViewById(R.id.seekbar_progress);
        mSeekVolume = findViewById(R.id.seekbar_volume);
        mSwitchMute = findViewById(R.id.sw_mute);
        mBtnDownload = findViewById(R.id.bt_download);
        mBtnAddMark = findViewById(R.id.bt_addMark);
        mEdtMark = findViewById(R.id.textMark);

        mDevicesAdapter = new DevicesAdapter(mContext);
        mDeviceList.setAdapter(mDevicesAdapter);

        /** 这里为了模拟 seek 效果(假设视频时间为 15s)，拖住 seekbar 同步视频时间，
         * 在实际中 使用的是片源的时间 */
        mSeekProgress.setMax(15);

        // 最大音量就是 100，不要问我为什么
        mSeekVolume.setMax(100);
    }

    private void initListeners() {
        mRefreshLayout.setOnRefreshListener(this);

        mDeviceList.setOnItemClickListener((parent, view, position, id) -> {
            // 选择连接设备
            ClingDevice item = mDevicesAdapter.getItem(position);
            if (Utils.isNull(item)) {
                return;
            }

            ClingManager.getInstance().setSelectedDevice(item);

            Device device = item.getDevice();
            if (Utils.isNull(device)) {
                return;
            }

            String selectedDeviceName = String.format(getString(R.string.selectedText), device.getDetails().getFriendlyName());
            mTVSelected.setText(selectedDeviceName);
        });

        // 设置发现设备监听
        mBrowseRegistryListener.setOnDeviceListChangedListener(new DeviceListChangedListener() {
            @Override
            public void onDeviceAdded(final IDevice device) {
                runOnUiThread(() -> mDevicesAdapter.add((ClingDevice) device));
            }

            @Override
            public void onDeviceRemoved(final IDevice device) {
                runOnUiThread(() -> mDevicesAdapter.remove((ClingDevice) device));
            }
        });

        // 静音开关
        mSwitchMute.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                mClingPlayControl.setMute(isChecked, new ControlCallback() {
                    @Override
                    public void success(IResponse response) {
                        Log.e(TAG, "setMute success");
                    }

                    @Override
                    public void fail(IResponse response) {
                        Log.e(TAG, "setMute fail");
                    }
                });
            }
        });

        mSeekProgress.setOnSeekBarChangeListener(this);
        mSeekVolume.setOnSeekBarChangeListener(this);
    }

    @Override
    public void onRefresh() {
        mRefreshLayout.setRefreshing(true);
        mDeviceList.setEnabled(false);

        mRefreshLayout.setRefreshing(false);
        refreshDeviceList();
        mDeviceList.setEnabled(true);
    }

    /**
     * 刷新设备
     */
    private void refreshDeviceList() {
        Collection<ClingDevice> devices = ClingManager.getInstance().getDmrDevices();
        ClingDeviceList.getInstance().setClingDeviceList(devices);
        if (devices != null) {
            mDevicesAdapter.clear();
            mDevicesAdapter.addAll(devices);
        }
    }

    public void onClick(View view) {
        int id = view.getId();
        switch (id) {
            case R.id.bt_download:
                download();
                break;

            case R.id.bt_addMark:
                addHlsMark();
                break;

            case R.id.bt_play:
                play();
                break;

            case R.id.bt_pause:
                pause();
                break;

            case R.id.bt_stop:
                stop();
                break;
        }
    }

    private void download() {
        if (EasyPermissions.hasPermissions(this, REQUESTED_PERMISSIONS)) {

            File dir = new File(downloadPath);
            // clear
            if (dir.isDirectory()) {
                File[] files = dir.listFiles();
                for (File file : files) {
                    if (!file.isDirectory()) { // 确保是文件而非目录
                        file.delete();
                    }
                }
            }
            String sCommand = "ffmpeg -y -i https://dist.qianxueyunke.com/data/User/admin/home/%E5%AE%89%E8%A3%85%E5%8C%85/share/output.mp4 -c:v libx264 -c:a aac -ar 44100 -ac 1 -f hls -hls_time 10 -hls_list_size 0 -hls_segment_filename "
                    + dir.toString() + "/output%06d.ts " + dir.toString() + "/output.m3u8";
            //sCommand = " -codecs";
            String[] commands = sCommand.split(" ");
            Log.d(TAG, String.format("ffmpeg command %s", sCommand));
            RxFFmpegInvoke.getInstance().runCommandAsync(commands, new RxFFmpegInvoke.IFFmpegListener() {
                @Override
                public void onFinish() {
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            mBtnDownload.setText(String.format("下载完成"));
                        }
                    });
                }
                @Override
                public void onProgress(int progress, long progressTime) {
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            mBtnDownload.setText(String.format("下载中 %d%%", progress));
                        }
                    });
                }
                @Override
                public void onCancel() {

                }
                @Override
                public void onError(String message) {
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            mBtnDownload.setText(String.format("下载失败 %s", message));
                        }
                    });
                }
            });
        } else {
            // 没有申请过权限，现在去申请
            EasyPermissions.requestPermissions(this, "请点击确定允许存储权限",
                    PERMISSION_REQ_ID, REQUESTED_PERMISSIONS);
        }
    }

    // 方法：创建水印图片并保存
    private boolean makeWaterMarkImageFile(Context context, String text, String filePath, int fontSize, int textColor) {
        // 初始化Paint对象
        Paint paint = new Paint();
        paint.setColor(textColor); // 文本颜色
        paint.setTextSize(fontSize); // 字体大小
        paint.setAntiAlias(true); // 抗锯齿
        paint.setTypeface(Typeface.DEFAULT); // 字体样式

        // 计算文本宽度和高度
        float baseline = -paint.ascent(); // ascent() is negative
        Rect bounds = new Rect();
        paint.getTextBounds(text, 0, text.length(), bounds);
        int textWidth = bounds.width();
        int textHeight = (int) (baseline + paint.descent());

        // 创建一个足够大的Bitmap
        Bitmap bitmap = Bitmap.createBitmap(textWidth + 20, textHeight + 20, Bitmap.Config.ARGB_8888); // +20是为了给文本周围留点空间

        // 创建一个Canvas来绘制Bitmap
        Canvas canvas = new Canvas(bitmap);

        // 绘制文本
        canvas.drawText(text, 10, baseline + 10, paint); // 文本位置稍微偏移一点

        // 保存Bitmap到文件
        File file = new File(filePath);
        try (FileOutputStream fos = new FileOutputStream(file)) {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, fos);
            fos.flush();
            return true;
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }

    private void addHlsMark() {
        String textMark = mEdtMark.getText().toString();
        if (textMark.isEmpty()) {
            Toast.makeText(MainActivity.this, "水印内容不能为空", Toast.LENGTH_SHORT).show();
            return;
        }
        String fileMark = downloadPath + "/waterMark.png";
        boolean success = makeWaterMarkImageFile(this, textMark, fileMark, 40, Color.GRAY);
        if (!success) {
            Toast.makeText(MainActivity.this, "创建水印失败", Toast.LENGTH_SHORT).show();
            return;
        }
        // 假设 downloadPath 是你的文件目录
        File dir = new File(downloadPath);
        File[] files = dir.listFiles((dir1, name) -> name.matches("output\\d{6}\\.ts"));

        if (files != null) {
            File tempFile = new File(downloadPath, "temp.ts");
            for (int i = 0; i < files.length; i++) {
                if (i % 2 == 1) { // 每隔3个文件处理一次
                    int nPos = (int) ((i * 100.0) / files.length);
                    mBtnAddMark.setText(String.format("进度 %d%%", nPos));
                    File sourceFile = files[i];
                    // 构造 FFmpeg 命令
                    String sCommand = String.format("-y -i %s -i %s -filter_complex overlay=x='abs(main_w-main_w*mod(t/10,2))':y='abs(main_h*mod(t/20,1))' -c:v libx264 -crf 23 %s",
                            sourceFile.getAbsolutePath(), fileMark, tempFile.getAbsolutePath());
                    String[] commands = sCommand.split(" ");
                    Log.d(TAG, String.format("ffmpeg command %s", sCommand));
                    RxFFmpegInvoke.getInstance().runCommand(commands, new RxFFmpegInvoke.IFFmpegListener() {
                        @Override
                        public void onFinish() {
                            // 删除原始文件
                            sourceFile.delete();
                            // 重命名临时文件为原始文件名
                            tempFile.renameTo(sourceFile);
                        }
                        @Override
                        public void onProgress(int progress, long progressTime) {

                        }
                        @Override
                        public void onCancel() {

                        }
                        @Override
                        public void onError(String message) {
                            mainHandler.post(new Runnable() {
                                @Override
                                public void run() {
                                    mBtnAddMark.setText(String.format("添加水印失败 %s", message));
                                }
                            });
                        }
                    });

                }
            }
            mBtnAddMark.setText(String.format("水印完成"));
        }
    }

    public String getAddressPlayUrl(Context context) {
        WifiManager wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        if (wifiManager != null) {
            int ipAddress = wifiManager.getConnectionInfo().getIpAddress();
            // 格式化IP地址
            return String.format("http://%d.%d.%d.%d:%d/output.m3u8",
                    (ipAddress & 0xff),
                    (ipAddress >> 8 & 0xff),
                    (ipAddress >> 16 & 0xff),
                    (ipAddress >> 24 & 0xff),
                    nHttpPort);
        }
        return "";
    }

    /**
     * 停止
     */
    private void stop() {
        mClingPlayControl.stop(new ControlCallback() {
            @Override
            public void success(IResponse response) {
                Log.e(TAG, "stop success");
            }

            @Override
            public void fail(IResponse response) {
                Log.e(TAG, "stop fail");
            }
        });
    }

    /**
     * 暂停
     */
    private void pause() {
        mClingPlayControl.pause(new ControlCallback() {
            @Override
            public void success(IResponse response) {
                Log.e(TAG, "pause success");
            }

            @Override
            public void fail(IResponse response) {
                Log.e(TAG, "pause fail");
            }
        });
    }

    public void getPositionInfo() {
        mClingPlayControl.getPositionInfo(new ControlReceiveCallback() {
            @Override
            public void receive(IResponse response) {

            }

            @Override
            public void success(IResponse response) {

            }

            @Override
            public void fail(IResponse response) {

            }
        });
    }

    /**
     * 播放视频
     */
    private void play() {
        String strUrl = getAddressPlayUrl(this);
        if (strUrl.isEmpty()) {
            Toast.makeText(MainActivity.this, "获取wifi地址失败" , Toast.LENGTH_LONG).show();
            return;
        }
        Log.i(TAG, "play " + strUrl);
        Toast.makeText(MainActivity.this, strUrl , Toast.LENGTH_LONG).show();


        @DLANPlayState.DLANPlayStates int currentState = mClingPlayControl.getCurrentState();
        /**
         * 通过判断状态 来决定 是继续播放 还是重新播放
         */
        if (currentState == DLANPlayState.STOP || true) {
            mClingPlayControl.playNew(strUrl, new ControlCallback() {

                @Override
                public void success(IResponse response) {
                    Log.e(TAG, "play success");
                    //                    ClingUpnpServiceManager.getInstance().subscribeMediaRender();
                    //                    getPositionInfo();
                    // TODO: 17/7/21 play success
                    ClingManager.getInstance().registerAVTransport(mContext);
                    ClingManager.getInstance().registerRenderingControl(mContext);
                }

                @Override
                public void fail(IResponse response) {
                    Log.e(TAG, "play fail");
                    mHandler.sendEmptyMessage(ERROR_ACTION);
                }
            });
        } else {
            mClingPlayControl.play(new ControlCallback() {
                @Override
                public void success(IResponse response) {
                    Log.e(TAG, "play success");
                }

                @Override
                public void fail(IResponse response) {
                    Log.e(TAG, "play fail");
                    mHandler.sendEmptyMessage(ERROR_ACTION);
                }
            });
        }
    }

    /******************* start progress changed listener *************************/

    @Override
    public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
    }

    @Override
    public void onStartTrackingTouch(SeekBar seekBar) {
        Log.e(TAG, "Start Seek");
    }

    @Override
    public void onStopTrackingTouch(SeekBar seekBar) {
        Log.e(TAG, "Stop Seek");
        int id = seekBar.getId();
        switch (id) {
            case R.id.seekbar_progress: // 进度

                int currentProgress = seekBar.getProgress() * 1000; // 转为毫秒
                mClingPlayControl.seek(currentProgress, new ControlCallback() {
                    @Override
                    public void success(IResponse response) {
                        Log.e(TAG, "seek success");
                    }

                    @Override
                    public void fail(IResponse response) {
                        Log.e(TAG, "seek fail");
                    }
                });
                break;

            case R.id.seekbar_volume:   // 音量

                int currentVolume = seekBar.getProgress();
                mClingPlayControl.setVolume(currentVolume, new ControlCallback() {
                    @Override
                    public void success(IResponse response) {
                        Log.e(TAG, "volume success");
                    }

                    @Override
                    public void fail(IResponse response) {
                        Log.e(TAG, "volume fail");
                    }
                });
                break;
        }
    }

    /******************* end progress changed listener *************************/

    private final class InnerHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            super.handleMessage(msg);
            switch (msg.what) {
                case PLAY_ACTION:
                    Log.i(TAG, "Execute PLAY_ACTION");
                    Toast.makeText(mContext, "正在投放", Toast.LENGTH_SHORT).show();
                    mClingPlayControl.setCurrentState(DLANPlayState.PLAY);

                    break;
                case PAUSE_ACTION:
                    Log.i(TAG, "Execute PAUSE_ACTION");
                    mClingPlayControl.setCurrentState(DLANPlayState.PAUSE);

                    break;
                case STOP_ACTION:
                    Log.i(TAG, "Execute STOP_ACTION");
                    mClingPlayControl.setCurrentState(DLANPlayState.STOP);

                    break;
                case TRANSITIONING_ACTION:
                    Log.i(TAG, "Execute TRANSITIONING_ACTION");
                    Toast.makeText(mContext, "正在连接", Toast.LENGTH_SHORT).show();
                    break;
                case ERROR_ACTION:
                    Log.e(TAG, "Execute ERROR_ACTION");
                    Toast.makeText(mContext, "投放失败", Toast.LENGTH_SHORT).show();
                    break;
            }
        }
    }

    /**
     * 接收状态改变信息
     */
    private class TransportStateBroadcastReceiver extends BroadcastReceiver {

        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            Log.e(TAG, "Receive playback intent:" + action);
            if (Intents.ACTION_PLAYING.equals(action)) {
                mHandler.sendEmptyMessage(PLAY_ACTION);

            } else if (Intents.ACTION_PAUSED_PLAYBACK.equals(action)) {
                mHandler.sendEmptyMessage(PAUSE_ACTION);

            } else if (Intents.ACTION_STOPPED.equals(action)) {
                mHandler.sendEmptyMessage(STOP_ACTION);

            } else if (Intents.ACTION_TRANSITIONING.equals(action)) {
                mHandler.sendEmptyMessage(TRANSITIONING_ACTION);
            }
        }
    }
}