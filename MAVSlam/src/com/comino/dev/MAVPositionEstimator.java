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

package com.comino.dev;


import java.awt.Graphics;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.LockSupport;

import org.mavlink.messages.MAV_SEVERITY;
import org.mavlink.messages.MSP_CMD;
import org.mavlink.messages.MSP_COMPONENT_CTRL;
import org.mavlink.messages.lquac.msg_msp_command;
import org.mavlink.messages.lquac.msg_msp_vision;
import org.mavlink.messages.lquac.msg_vision_position_estimate;
import org.mavlink.messages.lquac.msg_vision_speed_estimate;

import com.comino.dev.CoreSLAM;
import com.comino.main.MSPConfig;
import com.comino.mav.control.IMAVMSPController;
import com.comino.msp.execution.control.listener.IMAVLinkListener;
import com.comino.msp.model.DataModel;
import com.comino.msp.model.segment.LogMessage;
import com.comino.msp.model.segment.Status;
import com.comino.msp.utils.MSPMathUtils;
import com.comino.realsense.boofcv.RealSenseInfo;
import com.comino.realsense.boofcv.StreamRealSenseVisDepth;
import com.comino.realsense.boofcv.StreamRealSenseVisDepth.Listener;
import com.comino.server.mjpeg.IVisualStreamHandler;
import com.comino.slam.boofcv.odometry.FactoryMAVOdometry;
import com.comino.slam.boofcv.odometry.MAVDepthVisualOdometry;
import com.comino.slam.detectors.ISLAMDetector;
import com.comino.slam.estimators.IPositionEstimator;

import boofcv.abst.feature.detect.interest.ConfigGeneralDetector;
import boofcv.abst.feature.tracker.PointTrackerTwoPass;
import boofcv.abst.sfm.AccessPointTracks3D;
import boofcv.alg.distort.DoNothingPixelTransform_F32;
import boofcv.alg.sfm.DepthSparse3D;
import boofcv.alg.tracker.klt.PkltConfig;
import boofcv.core.image.ConvertImage;
import boofcv.factory.feature.tracker.FactoryPointTrackerTwoPass;
import boofcv.struct.image.GrayS16;
import boofcv.struct.image.GrayU16;
import boofcv.struct.image.GrayU8;
import boofcv.struct.image.Planar;
import georegression.geometry.ConvertRotation3D_F64;
import georegression.geometry.GeometryMath_F64;
import georegression.struct.EulerType;
import georegression.struct.point.Vector3D_F64;
import georegression.struct.se.Se3_F64;

public class MAVPositionEstimator implements IPositionEstimator {

	private static final int    INIT_TIME_MS    	= 600;
	private static final int    MAX_ERRORS    	    = 1;

	private static final float  MAX_SPEED   		= 15;

	private static final float  MAX_ROT_SPEED   	= 4f;

	private static final int    MIN_QUALITY 		= 15;

	private static final int    MAXTRACKS   		= 160;
	private static final int    RANSAC_ITERATIONS   = 150;
	private static final int    RETIRE_THRESHOLD    = 10;
	private static final int    INLIER_THRESHOLD    = 150;
	private static final int    REFINE_ITERATIONS   = 400;


	private StreamRealSenseVisDepth realsense;
	private MAVDepthVisualOdometry<GrayU8,GrayU16> visualOdometry;

	private GrayU8 gray = null;

	private double oldTimeDepth_us=0;
	private double estTimeDepth_us=0;

	private Vector3D_F64 	pos_raw;
	private Vector3D_F64 pos_raw_old = new Vector3D_F64();

	private Se3_F64 speed       	 = new Se3_F64();
	private Se3_F64 speed_ned        = new Se3_F64();
	private Se3_F64 speed_old        = new Se3_F64();
	private Se3_F64 pos_delta_ned    = new Se3_F64();
	private Se3_F64 pos_delta        = new Se3_F64();
	private Se3_F64 pos_ned          = new Se3_F64();

	private Se3_F64 rot_raw          = new Se3_F64();
	private Se3_F64 rot_ned          = new Se3_F64();

	private Se3_F64 vis_init         = new Se3_F64();

	private Se3_F64 cam_offset       = new Se3_F64();

	private Se3_F64 visToNED         = new Se3_F64();

	private double[] visAttitude     = new double[3];
	private double[] visAttitude_old = new double[3];

	private long fps_tms   =0;
	private long init_tms  =0;

	private DataModel model;

	private boolean debug = false;


	private int quality=0;
	private float fps = 0;

	private float low_pass   = 0;
	private float low_pass_a = 0;

	private boolean isRunning    = false;

	private IMAVMSPController control;

	private int error_count = 0;
	private int init_count = 0;

	private boolean do_position = false;
	private boolean do_odometry = true;
	private boolean do_speed    = true;

	private long detector_tms = 0;
	private int  detector_cycle_ms = 250;

	private List<ISLAMDetector> 		detectors = null;
	private List<IVisualStreamHandler>	streams = null;
	private RealSenseInfo info;

	private CoreSLAM slam = null;


	public MAVPositionEstimator(RealSenseInfo info, IMAVMSPController control, MSPConfig config, IVisualStreamHandler stream, CoreSLAM slam) {

		this.info    = info;
		this.slam    = slam;
		this.control = control;
		this.detectors = new ArrayList<ISLAMDetector>();
		this.streams   = new ArrayList<IVisualStreamHandler>();

		this.debug = config.getBoolProperty("vision_debug", "false");
		System.out.println("Vision debugging: "+debug);
		System.out.println("RANSAC iterations: "+RANSAC_ITERATIONS);

		this.do_odometry = config.getBoolProperty("vision_enable", "true");
		System.out.println("Vision Odometry enabled: "+do_odometry);
		this.do_speed    = config.getBoolProperty("vision_pub_speed", "true");
		System.out.println("Vision publishes speed: "+do_speed);
		this.do_position = config.getBoolProperty("vision_pub_pos", "true");
		System.out.println("Vision publishes position: "+do_position);
		this.low_pass = config.getFloatProperty("vision_speed_lowpass", "0.0");
		System.out.println("Vision speed lowpass factor: "+low_pass);
		this.low_pass_a = config.getFloatProperty("vision_attitude_lowpass", "0.0");
		System.out.println("Vision attitude lowpass factor: "+low_pass_a);

		this.detector_cycle_ms = config.getIntProperty("vision_detector_cycle", "0");
		if(this.detector_cycle_ms > 0)
			System.out.printf("Vision detectors enablied with %d [ms] cycle \n",detector_cycle_ms);

		this.cam_offset.T.z = -config.getFloatProperty("vision_x_offset", "0.0");
		this.cam_offset.T.x = -config.getFloatProperty("vision_y_offset", "0.0");
		this.cam_offset.T.y = -config.getFloatProperty("vision_z_offset", "0.0");

		System.out.printf("Vision position offset: %s\n\n",this.cam_offset.T);
		System.out.println("Resolution: "+info.width+"x"+info.height);

		this.model = control.getCurrentModel();

		gray = new GrayU8(info.width,info.height);

		control.registerListener(msg_msp_command.class, new IMAVLinkListener() {
			@Override
			public void received(Object o) {
				msg_msp_command cmd = (msg_msp_command)o;
				switch(cmd.command) {
				case MSP_CMD.MSP_CMD_VISION:
					switch((int)cmd.param1) {
					case MSP_COMPONENT_CTRL.ENABLE:
						do_odometry = true; init("Init"); break;
					case MSP_COMPONENT_CTRL.DISABLE:
						do_odometry = false; break;
					case MSP_COMPONENT_CTRL.RESET:
						reset(); break;
					}
				}
			}
		});

		try {
			realsense = new StreamRealSenseVisDepth(0,info);
		} catch(Exception e) {	}

		PkltConfig configKlt = new PkltConfig();
		configKlt.pyramidScaling = new int[]{1, 4, 16};
		configKlt.templateRadius = 3;

		PointTrackerTwoPass<GrayU8> tracker =
				FactoryPointTrackerTwoPass.klt(configKlt, new ConfigGeneralDetector(MAXTRACKS, 1, 0.5f),
						GrayU8.class, GrayS16.class);

		DepthSparse3D<GrayU16> sparseDepth = new DepthSparse3D.I<GrayU16>(1e-3);

		visualOdometry = FactoryMAVOdometry.depthDepthPnP(1.5,
				INLIER_THRESHOLD, RETIRE_THRESHOLD, RANSAC_ITERATIONS, REFINE_ITERATIONS, true,
				sparseDepth, tracker, GrayU8.class, GrayU16.class);

		visualOdometry.setCalibration(realsense.getIntrinsics(),new DoNothingPixelTransform_F32());

		if(stream!=null) {
			registerStreams(stream);

			if(debug && streams.get(0) !=null) {
				streams.get(0).registerOverlayListener(ctx -> {
					overlayFeatures(ctx);
				});
			}
		}

		init_count = 0;

		realsense.registerListener(new Listener() {

			double dt; int mf=0; int fpm; float head_div;
			float ang_speed; float odo_speed;
			int qual_error_count=0;

			@Override
			public void process(Planar<GrayU8> rgb, GrayU16 depth, long timeRgb, long timeDepth) {


				if(dt >0) {
					fpm += (int)(1f/dt+0.5f);
					if((System.currentTimeMillis() - fps_tms) > 500) {
						fps_tms = System.currentTimeMillis();
						if(mf>0)
							fps = fpm/mf;
						mf=0; fpm=0;
					}
					mf++;
				}

				try {
					ConvertImage.average(rgb, gray);

					for(IVisualStreamHandler stream : streams)
						stream.addToStream(rgb,model, System.nanoTime()/1000);


					// Check PX4 rotation and reset odometry if rotating too fast
					ang_speed = (float)Math.sqrt(model.attitude.pr * model.attitude.pr +
							model.attitude.rr * model.attitude.rr +
							model.attitude.yr * model.attitude.yr);

					if(ang_speed > MAX_ROT_SPEED) {
						if(debug)
							System.out.println("[vis] Rotation speed "+ang_speed+" > MAX");
						init("Rotation speed");
						return;
					}


					if( !visualOdometry.process(gray,depth)) {
						if(debug)
							System.out.println("[vis] Odometry failure");
						init("Odometry");
						return;
					}

				} catch( Exception e) {
					if(debug)
						System.out.println("[vis] Odometry failure: "+e.getMessage());
					init("Exception");
				}

				quality = (int)(visualOdometry.getQuality() * 100f / MAXTRACKS);
				if(quality > 100) quality = 100;

				if((System.currentTimeMillis()-init_tms) < INIT_TIME_MS) {

					if( quality > MIN_QUALITY) {
						vis_init.getTranslation().z = vis_init.getTranslation().z * init_count + model.attitude.r;
						vis_init.getTranslation().x = vis_init.getTranslation().x * init_count + model.attitude.p;
						vis_init.getTranslation().y = vis_init.getTranslation().y * init_count + model.attitude.y;

						vis_init.getTranslation().scale(1d/(++init_count));

						//	ConvertRotation3D_F64.eulerToMatrix(EulerType.ZXY,
						ConvertRotation3D_F64.eulerToMatrix(EulerType.XYZ,
								vis_init.getTranslation().x,
								vis_init.getTranslation().y,
								vis_init.getTranslation().z,
								visToNED.getRotation());

						speed_old.reset();
						pos_ned.reset();
						pos_raw_old.set(0,0,0);
					} else {
						init_tms = System.currentTimeMillis();
					}
					return;
				}
				//

				//estTimeDepth_us = timeDepth*1000;
				estTimeDepth_us = System.nanoTime()/1000f;
				if(oldTimeDepth_us>0)
					dt = (estTimeDepth_us - oldTimeDepth_us)/1000000f;
				oldTimeDepth_us = estTimeDepth_us;

				pos_raw = visualOdometry.getCameraToWorld().getT();
				rot_raw.setRotation(visualOdometry.getCameraToWorld().getR());

				if(!pos_raw_old.isIdentical(0, 0, 0) && dt > 0) {

					if(quality > MIN_QUALITY ) {

						speed.reset();
						// Add camera offset to pos_raw
						pos_raw = pos_raw.plus(cam_offset.T);

						// speed.T = (pos_raw - pos_raw_old ) / dt
						GeometryMath_F64.sub(pos_raw, pos_raw_old, speed.T);
						speed.T.scale(1d/dt);

					} else {
						if(++qual_error_count > 5) {
							qual_error_count=0;
							if(debug)
								System.out.println("[vis] Quality "+quality+" < Min");
							init("Quality");
						}
						return;
					}

					if(low_pass > 0) {
						// Low pass speed.T = speed.T * (1 - low_pass) + old_speed.T * low_pass
						speed.T.scale(1-low_pass); speed_old.T.scale(low_pass);
						speed.T.plusIP(speed_old.T);
					}

					odo_speed = (float) speed.T.norm();
					speed_old.T.set(speed.T);

					if(odo_speed < MAX_SPEED) {

						// pos_delta.T = speed.T * dt
						pos_delta.T.set(speed.T); pos_delta.T.scale(dt);
						// rotate to NED
						pos_delta.concat(visToNED, pos_delta_ned);
						speed.concat(visToNED, speed_ned);


					} else {
						init("Odometry speed");
						return;
					}

					// pos.T = pos.T + pos_delta.T
					pos_ned.T.plusIP(pos_delta_ned.T);

					// Get rotations based on vision 0=roll,1=pitch,2=yaw
					rot_raw.concat(visToNED, rot_ned);

					ConvertRotation3D_F64.matrixToEuler(rot_ned.R, EulerType.ZXY, visAttitude);

					// Attitude low pass
					if(low_pass_a > 0) {
						for(int i=0;i<3;i++) {
							visAttitude[i] = visAttitude[i] * (1 - low_pass_a) + visAttitude_old[i] * (low_pass_a);
							visAttitude_old[i] = visAttitude[i];
						}
					}

					// In landed state be more accurate
					head_div = model.sys.isStatus(Status.MSP_LANDED) ? 0.1f : 0.1f;

					if(Math.abs(visAttitude[2] - model.attitude.y) > head_div) {
						if(debug)
							System.out.println("[vis] Heading not valid");
						init("Heading div.");
						return;
					}


				}
				pos_raw_old.set(pos_raw);

				if(control!=null) {
					if(error_count < MAX_ERRORS)
						publishPX4Vision();
					LockSupport.parkNanos(2000000);
					error_count=0;
					publisMSPVision();
				}

				if(detectors.size()>0 && detector_cycle_ms>0) {
					if((System.currentTimeMillis() - detector_tms) > detector_cycle_ms) {
						detector_tms = System.currentTimeMillis();
						for(ISLAMDetector d : detectors)
							d.process(visualOdometry, depth, gray);
					}
				}
			}
		});
		init_tms = System.currentTimeMillis()+5000;
	}

	private void overlayFeatures(Graphics ctx) {

		AccessPointTracks3D points = (AccessPointTracks3D)visualOdometry;
		for( int i = 0; i < points.getAllTracks().size(); i++ ) {
			if(points.isInlier(i))
				ctx.drawRect((int)points.getAllTracks().get(i).x,(int)points.getAllTracks().get(i).y, 1, 1);
		}
		ctx.drawString((int)fps+" fps", info.width-50, 20);
	}

	public MAVPositionEstimator() {
		this(new RealSenseInfo(320,240, RealSenseInfo.MODE_RGB), null, MSPConfig.getInstance(),null,null);
	}

	/* (non-Javadoc)
	 * @see com.comino.slam.estimators.IPositionEstimator#registerDetector(com.comino.slam.detectors.ISLAMDetector)
	 */
	@Override
	public void registerDetector(ISLAMDetector detector) {
		if(detector_cycle_ms>0) {
			System.out.println("[vis] Vision detector registered: "+detector.getClass().getSimpleName());
			detectors.add(detector);
		}
	}

	/* (non-Javadoc)
	 * @see com.comino.slam.estimators.IPositionEstimator#registerStreams(com.comino.server.mjpeg.IVisualStreamHandler)
	 */
	@Override
	public void registerStreams(IVisualStreamHandler stream) {
		System.out.println("[vis] Vision stream registered: "+stream.getClass().getSimpleName());
		streams.add(stream);
	}

	/* (non-Javadoc)
	 * @see com.comino.slam.estimators.IPositionEstimator#start()
	 */
	@Override
	public void start() {
		isRunning = true; init_tms=0;
		init("StartUp");
		if(realsense!=null)
			realsense.start();
	}

	/* (non-Javadoc)
	 * @see com.comino.slam.estimators.IPositionEstimator#stop()
	 */
	@Override
	public void stop() {
		if(isRunning) {
			realsense.stop();
			publisMSPVision();
		}
		isRunning=false;
	}

	/* (non-Javadoc)
	 * @see com.comino.slam.estimators.IPositionEstimator#isRunning()
	 */
	@Override
	public boolean isRunning() {
		return isRunning;
	}

	/* (non-Javadoc)
	 * @see com.comino.slam.estimators.IPositionEstimator#reset()
	 */
	@Override
	public void reset() {
		init_tms=0;
		init("msp reset");
	}

	private void init(String reason) {
		if((System.currentTimeMillis()-init_tms)>INIT_TIME_MS) {
			if(do_odometry) {
				error_count++;
				if((error_count % MAX_ERRORS)==0) {
					control.writeLogMessage(new LogMessage("[vis] reset odometry: "+reason,
							MAV_SEVERITY.MAV_SEVERITY_NOTICE));
					if(debug)
						System.out.println("[vis] reset odometry: "+reason);
				}
				visualOdometry.reset();
				init_count = 0; fps=0; quality=0;
				vis_init.reset();
				init_tms = System.currentTimeMillis();
				publisMSPVision();

				if(detectors.size()>0) {
					detector_tms = System.currentTimeMillis();
					for(ISLAMDetector d : detectors)
						d.reset(model.state.l_x, model.state.l_y, model.state.l_z);
				}
			}
		}
	}

	private void publishPX4Vision() {

		if(do_position && do_odometry) {
			msg_vision_position_estimate sms = new msg_vision_position_estimate(1,2);
			sms.usec = System.nanoTime()/1000;;
			//sms.usec = (long)estTimeDepth_us;
			sms.x = (float) pos_ned.T.z;
			sms.y = (float) pos_ned.T.x;
			sms.z = (float) pos_ned.T.y;
			sms.roll  = (float)visAttitude[0];
			sms.pitch = (float)visAttitude[1];
			sms.yaw   = (float)visAttitude[2];
			control.sendMAVLinkMessage(sms);
		}

		if(do_speed && do_odometry) {
			msg_vision_speed_estimate sse = new msg_vision_speed_estimate(1,2);
			sse.usec = System.nanoTime()/1000;
			sse.x = (float) speed_ned.T.z;
			sse.y = (float) speed_ned.T.x;
			sse.z = (float) speed_ned.T.y;
			sse.isValid = true;
			control.sendMAVLinkMessage(sse);
		}
	}

	private void publisMSPVision() {
		msg_msp_vision msg = new msg_msp_vision(2,1);
		msg.x =  (float) pos_ned.T.z;
		msg.y =  (float) pos_ned.T.x;
		msg.z =  (float) pos_ned.T.y;
		msg.vx = (float) speed_ned.T.z;
		msg.vy = (float) speed_ned.T.x;
		msg.vz = (float) speed_ned.T.y;
		msg.h = MSPMathUtils.fromRad((float)visAttitude[2]);   //MSPMathUtils.fromRad((float)vis_init.getY());
		msg.p = (float)visAttitude[1];
		msg.r = (float)visAttitude[0];
		msg.quality = quality;
		msg.fps = fps;
		msg.errors = error_count;
		if(do_position && do_odometry)
			msg.flags = msg.flags | 1;
		if(do_speed && do_odometry)
			msg.flags = msg.flags | 2;
		msg.tms = (long)estTimeDepth_us;
		control.sendMAVLinkMessage(msg);
	}

	public static void main(String[] args) {
		new MAVPositionEstimator();
	}

	private Se3_F64 getAttitudeToState(DataModel m, Se3_F64 state) {
		ConvertRotation3D_F64.eulerToMatrix(EulerType.ZXY,
				m.attitude.r,
				m.attitude.p,
				m.attitude.y,
				state.getRotation());
		return state;
	}

}
