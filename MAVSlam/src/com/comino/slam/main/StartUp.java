/****************************************************************************
 *
 *   Copyright (c) 2017 Eike Mansfeld ecm@gmx.de. All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met:
 *
 * 1. Redistributions of source code must retain the above copyright
 *    notice, this list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright
 *    notice, this list of conditions and the following disclaimer in
 *    the documentation and/or other materials provided with the
 *    distribution.
 * 3. Neither the name of the copyright holder nor the names of its
 *    contributors may be used to endorse or promote products derived
 *    from this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
 * LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS
 * FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE
 * COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT,
 * INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING,
 * BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS
 * OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED
 * AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT
 * LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN
 * ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED OF THE
 * POSSIBILITY OF SUCH DAMAGE.
 *
 ****************************************************************************/

package com.comino.slam.main;

import java.io.IOException;
import java.lang.management.MemoryMXBean;
import java.lang.management.OperatingSystemMXBean;
import java.net.InetSocketAddress;

import org.mavlink.messages.MSP_SYSTEM_STATUS;
import org.mavlink.messages.lquac.msg_gps_global_origin;
import org.mavlink.messages.lquac.msg_msp_micro_grid;
import org.mavlink.messages.lquac.msg_msp_status;
import org.mavlink.messages.lquac.msg_timesync;

import com.comino.main.MSPConfig;
import com.comino.mav.control.IMAVMSPController;
import com.comino.mav.control.impl.MAVController;
import com.comino.mav.control.impl.MAVProxyController;
import com.comino.msp.execution.autopilot.Autopilot2D;
import com.comino.msp.execution.commander.MSPCommander;
import com.comino.msp.log.MSPLogger;
import com.comino.msp.model.DataModel;
import com.comino.msp.model.segment.Status;
import com.comino.msp.utils.upboard.CPUTemperature;
import com.comino.msp.utils.upboard.UpLEDControl;
import com.comino.msp.utils.upboard.WifiQuality;
import com.comino.realsense.boofcv.RealSenseInfo;
import com.comino.server.mjpeg.impl.HttpMJPEGHandler;
import com.comino.slam.detectors.impl.VfhDirectDepthDetector;
import com.comino.slam.detectors.impl.VfhFeatureDetector;
import com.comino.slam.estimators.IPositionEstimator;
import com.comino.slam.estimators.MAVVisualPositionEstimator;
import com.sun.net.httpserver.HttpServer;

import boofcv.alg.filter.blur.BlurImageOps;
import boofcv.alg.filter.misc.AverageDownSampleOps;
import boofcv.factory.filter.blur.FactoryBlurFilter;
import boofcv.struct.image.GrayU8;

public class StartUp implements Runnable {

	IMAVMSPController    control = null;
	MSPConfig	          config  = null;

	private OperatingSystemMXBean osBean = null;
	private MemoryMXBean mxBean = null;

	private HttpMJPEGHandler streamer = null;

	private MSPCommander  commander = null;
	private final long startTime_ms = System.currentTimeMillis();

	IPositionEstimator vision = null;
	private boolean publish_microslam;
	private boolean is_simulation;

	public StartUp(String[] args) {

		if(args.length != 0) {
			is_simulation = true;
		}

		RealSenseInfo info = null;

		config  = MSPConfig.getInstance("/home/up","msp.properties");
		System.out.println("MSPControlService version "+config.getVersion());

		if(is_simulation)
			control = new MAVProxyController(MAVController.MODE_SITL);
		else
			control = new MAVProxyController(MAVController.MODE_NORMAL);

		osBean =  java.lang.management.ManagementFactory.getOperatingSystemMXBean();
		mxBean = java.lang.management.ManagementFactory.getMemoryMXBean();

		MSPLogger.getInstance(control);

		commander = new MSPCommander(control,config);

		control.start();

		Runtime.getRuntime().addShutdownHook(new Thread() {
			public void run() {
				if(vision!=null)
					vision.stop();
			}
		});

		MSPLogger.getInstance().writeLocalMsg("MAVProxy "+config.getVersion()+" loaded");
		//if(!is_simulation) {
		Thread worker = new Thread(this);
		worker.setPriority(Thread.MIN_PRIORITY);
		worker.setName("Main");
		worker.start();
		//	}

		// Start services if required

		try {
			if(config.getBoolProperty("vision_enabled", "true")) {

				if(config.getBoolProperty("vision_highres", "false"))
					info = new RealSenseInfo(640,480, RealSenseInfo.MODE_RGB);
				else
					info = new RealSenseInfo(320,240, RealSenseInfo.MODE_RGB);


				streamer = new HttpMJPEGHandler(info, control.getCurrentModel());

				// Start HTTP Service with MJPEG streamer

				vision = new MAVVisualPositionEstimator(info, control, config, streamer);
				//	vision.registerDetector(new VfhFeatureDetector(control,config,streamer));
				vision.registerDetector(new VfhDirectDepthDetector(control,config,streamer));

				HttpServer server;
				try {
					server = HttpServer.create(new InetSocketAddress(8080),2);
					server.createContext("/mjpeg", streamer);
					server.setExecutor(null); // creates a default executor
					server.start();
				} catch (IOException e) {
					System.err.println(e.getMessage());
				}

			}
		} catch(Exception e) { System.out.println("No vision available"); }


		this.publish_microslam = config.getBoolProperty("slam_publish_microslam", "true");
		System.out.println("[vis] Publishing microSlam enabled: "+publish_microslam);


		if(vision!=null && !vision.isRunning()) {
			vision.start();

		}

		// register MSP commands here

		Autopilot2D.getInstance().reset(true);

	}

	public static void main(String[] args)  {

		if(args.length==0)
			UpLEDControl.clear();

		new StartUp(args);

	}


	@Override
	public void run() {
		long tms = System.currentTimeMillis();
		long blink = tms;

		DataModel model = control.getCurrentModel();

		WifiQuality wifi = new WifiQuality();
		CPUTemperature temp = new CPUTemperature();
		msg_msp_micro_grid grid = new msg_msp_micro_grid(2,1);
		msg_msp_status msg = new msg_msp_status(2,1);

		while(true) {
			try {

				if(!control.isConnected()) {
					Thread.sleep(200);
					control.connect();
					continue;
				}

				if(publish_microslam && model.grid.hasTransfers()) {
					grid.resolution = 0.05f;
					grid.extension  = 0;
					grid.cx  = model.grid.getIndicatorX();
					grid.cy  = model.grid.getIndicatorY();
					grid.cz  = model.grid.getIndicatorZ();
					grid.tms = model.sys.getSynchronizedPX4Time_us();
					grid.count = model.grid.count;
					if(model.grid.toArray(grid.data))
						control.sendMAVLinkMessage(grid);
				}

           //     streamer.addToStream(Autopilot2D.getInstance().getMap2D().getMap().subimage(400-160, 400-120, 400+160, 400+120), model, System.currentTimeMillis()*1000);

				Thread.sleep(50);

				if((System.currentTimeMillis()-tms) < 500)
					continue;

				tms = System.currentTimeMillis();

				msg_timesync sync_s = new msg_timesync(255,1);
				sync_s.tc1 = 0;
				sync_s.ts1 = System.currentTimeMillis()*1000000L;//control.getCurrentModel().sys.getSynchronizedPX4Time_us()*1000;
				control.sendMAVLinkMessage(sync_s);

				wifi.getQuality();
				temp.getTemperature();

				msg.load = (int)(osBean.getSystemLoadAverage()*100)/4;
				msg.memory = (int)(mxBean.getHeapMemoryUsage().getUsed() * 100 /mxBean.getHeapMemoryUsage().getMax());
				msg.wifi_quality = (byte)wifi.get();
				msg.threads = Thread.activeCount();
				msg.cpu_temp = (byte)temp.get();
				msg.com_error = control.getErrorCount();
				msg.autopilot_mode =control.getCurrentModel().sys.autopilot;
				msg.uptime_ms = System.currentTimeMillis() - startTime_ms;
				msg.status = control.getCurrentModel().sys.getStatus();
				msg.setVersion(config.getVersion());
				msg.setArch(osBean.getArch());
				msg.unix_time_us = System.currentTimeMillis() * 1000;
				control.sendMAVLinkMessage(msg);

				if((System.currentTimeMillis()-blink) < 5000 || is_simulation)
					continue;

				blink = System.currentTimeMillis();

				if(model.sys.isStatus(Status.MSP_CONNECTED))
					UpLEDControl.flash("green", 10);
				else
					UpLEDControl.flash("red", 10);

			} catch (Exception e) {
				e.printStackTrace();
				control.close();
			}
		}
	}
}
