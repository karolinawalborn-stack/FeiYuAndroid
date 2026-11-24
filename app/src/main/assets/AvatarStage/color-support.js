// Cubism 4.2+ multiply/screen colors, including the model's nose highlights.
(() => {
 const sdk=PIXI.live2d;
 const shaders=sdk.CubismShader_WebGL.prototype;
 const compile=shaders.loadShaderProgram;
 shaders.loadShaderProgram=function(vertex,fragment){
   if(!fragment.includes('float isInside')) {
     fragment=fragment.replace('precision mediump float;','precision mediump float; uniform vec4 u_multiplyColor; uniform vec4 u_screenColor;');
     const sample='vec4 source=texture2D(s_texture0, v_texCoord); source.rgb*=u_multiplyColor.rgb; source.rgb=source.rgb+u_screenColor.rgb*source.a-source.rgb*u_screenColor.rgb;';
     fragment=fragment.replace('void main(){','void main(){'+sample)
       .replace('texture2D(s_texture0 , v_texCoord) * u_baseColor','source * u_baseColor')
       .replace('texture2D(s_texture0, v_texCoord) * u_baseColor','source * u_baseColor');
   }
   return compile.call(this,vertex,fragment);
 };
 const setup=shaders.setupShaderProgram;
 const locations=new WeakMap();
 shaders.setupShaderProgram=function(...args){
   setup.apply(this,args);
   const renderer=args[0];
   if(renderer.getClippingContextBufferForMask())return;
   const gl=this.gl,mask=renderer.getClippingContextBufferForDraw();
   const program=this._shaderSets[1+(mask?(args[12]?2:1):0)].shaderProgram;
   let loc=locations.get(program);
   if(!loc){loc={multiply:gl.getUniformLocation(program,'u_multiplyColor'),screen:gl.getUniformLocation(program,'u_screenColor')};locations.set(program,loc);}
   const data=renderer.getModel()._model.drawables,i=renderer.getModel().__colorIndex;
   if(Number.isInteger(i)){
     gl.uniform4fv(loc.multiply,data.multiplyColors.subarray(i*4,i*4+4));
     gl.uniform4fv(loc.screen,data.screenColors.subarray(i*4,i*4+4));
   }else{gl.uniform4f(loc.multiply,1,1,1,1);gl.uniform4f(loc.screen,0,0,0,1);}
 };
 const opacity=sdk.CubismModel.prototype.getDrawableOpacity;
 sdk.CubismModel.prototype.getDrawableOpacity=function(i){this.__colorIndex=i;return opacity.call(this,i);};
})();
