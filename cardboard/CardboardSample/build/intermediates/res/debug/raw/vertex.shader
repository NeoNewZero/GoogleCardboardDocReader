// Input vertex data, different for all executions of this shader.
attribute vec3 a_vertex;
attribute vec2 a_UV;

// Output data ; will be interpolated for each fragment.
varying vec2 UV;

uniform mat4 MVP;

void main(){

	// Output position of the vertex, in clip space
	// map [0..800][0..600] to [-1..1][-1..1]
	//vec3 vertexPosition_homoneneousspace = aVertexPosition; //- vec3(400,300,500);
	//vertexPosition_homoneneousspace /= vec3(100,100,100);
	//gl_Position = vec4(vertexPosition_homoneneousspace,1);



	//vec4 pos = vec4(aVertexPosition,1);
	//pos.z = ((0.1+100)/(0.1-100))*aVertexPosition.z + 2*100*0.10/(0.1-100);
	//pos.w = -aVertexPosition.z;


	gl_Position =  MVP*vec4(a_vertex,1);

	// UV of the vertex. No special space for this one.
	UV = a_UV;
}
