import Toybox.BluetoothLowEnergy;
import Toybox.Lang;
import Toybox.StringUtil;
import Toybox.System;


// ============================================================
// Garmin fenix 7X BLE Central
//
// 手机(Android):
//      BLE GATT Server
//          |
//          | Notify
//          |
// Garmin:
//      BLE Central
//
// Service:
// ba1d0001-5c3a-4e2b-9f8d-6a7c1e2f3a4b
//
// Characteristics:
// ba1d0002-5c3a-4e2b-9f8d-6a7c1e2f3a4b
//      Navigation data
//
// ba1d0003-5c3a-4e2b-9f8d-6a7c1e2f3a4b
//      Road name
//
// ============================================================


class NavBleDelegate extends BluetoothLowEnergy.BleDelegate {


    hidden var mSvcUuid;

    hidden var mNavUuid;

    hidden var mRoadUuid;


    hidden var mDevice = null;


    // 0: subscribe nav
    // 1: subscribe road
    hidden var mSubscribeStep = 0;



    function initialize() {

        BleDelegate.initialize();


        mSvcUuid =
            BluetoothLowEnergy.stringToUuid(
                "ba1d0001-5c3a-4e2b-9f8d-6a7c1e2f3a4b"
            );


        mNavUuid =
            BluetoothLowEnergy.stringToUuid(
                "ba1d0002-5c3a-4e2b-9f8d-6a7c1e2f3a4b"
            );


        mRoadUuid =
            BluetoothLowEnergy.stringToUuid(
                "ba1d0003-5c3a-4e2b-9f8d-6a7c1e2f3a4b"
            );

    }



    // ============================================================
    // Open BLE
    //
    // 注意:
    // fenix 7X 作为 Central
    // 不调用 registerProfile()
    //
    // registerProfile 是 GATT Server 用法
    // ============================================================


    function open() {


        BluetoothLowEnergy.setDelegate(self);


        startScan();

    }




    function close() {


        try {

            BluetoothLowEnergy.setScanState(
                BluetoothLowEnergy.SCAN_STATE_OFF
            );

        }
        catch(ex) {

        }



        if (mDevice != null) {


            try {

                BluetoothLowEnergy.unpairDevice(
                    mDevice
                );

            }
            catch(ex) {

            }


            mDevice = null;

        }


    }




    // ============================================================
    // Start scanning
    // ============================================================


    hidden function startScan() {


        NavData.bleState = 1;


        try {


            BluetoothLowEnergy.setScanState(
                BluetoothLowEnergy.SCAN_STATE_SCANNING,
                {
                    :scanMode =>
                        BluetoothLowEnergy.SCAN_MODE_LOW_LATENCY
                }
            );


        }
        catch(ex) {


        }


    }




    // ============================================================
    // BLE delegate callbacks
    // ============================================================



    function onScanResults(scanResults) {


        var item = scanResults.next();



        while(item != null) {



            // Monkey C next() 返回 Object
            // 必须转换成 ScanResult


            var result =
                item as BluetoothLowEnergy.ScanResult;



            if(result != null) {



                var uuids =
                    result.getServiceUuids();



                if(uuids != null) {



                    var uuid =
                        uuids.next();



                    while(uuid != null) {



                        if(uuid.equals(mSvcUuid)) {



                            try {



                                BluetoothLowEnergy.setScanState(
                                    BluetoothLowEnergy.SCAN_STATE_OFF
                                );



                                mDevice =
                                    BluetoothLowEnergy.pairDevice(
                                        result
                                    );



                            }
                            catch(ex) {



                                startScan();


                            }



                            return;


                        }



                        uuid =
                            uuids.next();


                    }


                }


            }



            item =
                scanResults.next();


        }


    }






    function onConnectedStateChanged(device,state) {



        if(state ==
            BluetoothLowEnergy.CONNECTION_STATE_CONNECTED) {



            NavData.bleState = 2;



            mDevice = device;



            mSubscribeStep = 0;



            subscribeNext();



        }

        else {



            NavData.bleState = 1;



            if(mDevice != null) {



                try {



                    BluetoothLowEnergy.unpairDevice(
                        mDevice
                    );



                }
                catch(ex) {



                }


                mDevice = null;


            }



            startScan();


        }


    }





    // ============================================================
    // Subscribe notification
    //
    // GATT:
    // nav characteristic notify
    // road characteristic notify
    //
    // 一次只写一个 CCCD
    // ============================================================



    hidden function subscribeNext() {


        if(mDevice == null) {

            return;

        }



        var service =
            mDevice.getService(
                mSvcUuid
            );



        if(service == null) {

            return;

        }



        var uuid;



        if(mSubscribeStep == 0) {


            uuid = mNavUuid;


        }

        else {


            uuid = mRoadUuid;


        }





        var characteristic =
            service.getCharacteristic(
                uuid
            );



        if(characteristic == null) {

            return;

        }



        var descriptor =
            characteristic.getDescriptor(
                BluetoothLowEnergy.cccdUuid()
            );



        if(descriptor == null) {

            return;

        }



        try {


            descriptor.requestWrite(
                [0x01,0x00]b
            );


        }
        catch(ex) {



        }



    }




    function onDescriptorWrite(
        descriptor,
        status
    ) {



        if(status != 0) {

            return;

        }



        if(mSubscribeStep == 0) {



            mSubscribeStep = 1;



            subscribeNext();



        }


    }
