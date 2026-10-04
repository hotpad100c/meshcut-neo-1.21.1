package ml.mypals.meshcut.client;

import org.joml.Matrix4f;
import org.joml.Vector3f;

/** Centered gun-model coordinates and vanilla player/held-item transforms, shared by drawing and beam origins. */
public final class GunPose {
    public static Vector3f muzzle() { return new Vector3f(0, 6.6f/16 - 0.5f, 0.05f/16 - 0.5f); }

    public static Matrix4f firstPerson(boolean right, float recoil, float equip, float projectionY) {
        int side=right?1:-1;
        float fovScale=1/(Math.max(0.01f,projectionY)*(float)Math.tan(Math.toRadians(35)));
        return new Matrix4f().scale(fovScale,fovScale,1)
                .translate(side*0.46f,-0.05f-equip*0.6f,-0.95f+recoil*0.18f)
                .rotateY((float)Math.toRadians(side*-8)).rotateZ((float)Math.toRadians(side*-4))
                .rotateX((float)Math.toRadians(recoil*5)).scale(0.85f);
    }

    public record Arm(float x,float y,float z,float pitch,float yaw) {}

    public static Arm arm(boolean right, boolean crouch, float relativeYaw, float pitch, float recoil) {
        float yaw=(float)Math.toRadians(relativeYaw), angle=(float)Math.toRadians(pitch)- (float)Math.PI/2-recoil*0.06f;
        Vector3f back=new Vector3f(0,-1,0).rotateX(angle).rotateY(yaw).mul(recoil*1.6f);
        return new Arm((right?-5:5)+back.x,(crouch?5.2f:2)+back.y,back.z,angle,yaw);
    }

    public static Matrix4f thirdPerson(boolean right, boolean crouch, float bodyYaw, float aimYaw, float pitch, float scale, float recoil) {
        Arm a=arm(right,crouch,aimYaw-bodyYaw,pitch,recoil);
        float modelScale=0.9375f*scale; // PlayerRenderer's skin-model scale.
        return new Matrix4f().translate(0,1.501f*modelScale-(crouch?0.125f*scale:0),0)
                .rotateY((float)Math.toRadians(180-bodyYaw)).scale(-modelScale,-modelScale,modelScale)
                .translate(a.x/16,a.y/16,a.z/16).rotateY(a.yaw).rotateX(a.pitch)
                // ItemInHandLayer: translateToHand, rotate X -90/Y 180, hand offset.
                .rotateX(-(float)Math.PI/2).rotateY((float)Math.PI)
                .translate((right?1:-1)/16f,0.125f,-0.625f)
                // laser_gun.json third-person display; positions below are already centered.
                .translate(0,3.2f/16,-2.1f/16).scale(0.8f);
    }
    private GunPose() {}
}
