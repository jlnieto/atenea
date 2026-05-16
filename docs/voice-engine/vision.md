# Vision

Atenea Voice Engine es la capa de interaccion por voz de Atenea.

Debe permitir al operador trabajar con Atenea con auriculares, caminando, esperando, viajando o sin mirar la pantalla. La experiencia objetivo es una conversacion natural: el operador habla, Atenea entiende el foco actual, responde con una voz femenina humana, permite interrupciones, guarda notas sin romper el flujo y puede continuar por donde iba.

## Objetivo

Construir una interfaz por voz de maxima calidad para:

- operar conversaciones de `WorkSession`;
- revisar avances de Codex;
- navegar entre proyectos;
- gestionar comunicaciones;
- consultar operaciones;
- tomar notas temporales;
- transformar notas en prompts;
- confirmar acciones sensibles;
- mantener foco operativo sin ambiguedad.

## No objetivo

No se busca una simple funcion de dictado.

No se busca que el usuario tenga que mirar la pantalla para entender si Atenea esta escuchando, pensando, hablando o esperando confirmacion.

No se busca que cada pantalla implemente su propio audio de forma aislada.

## Principios

1. Foco antes que comando.

   Atenea debe saber en que dominio esta antes de interpretar una instruccion. Un comando como `haz esto` no significa nada sin foco.

2. Voz primero, pantalla como apoyo.

   La experiencia debe poder usarse con auriculares. La pantalla muestra estado y detalle, pero no debe ser necesaria para cada paso.

3. Resumen antes que lectura larga.

   Para respuestas largas, Atenea resume primero y pregunta si quieres detalle. Esto evita escuchar bloques largos sin control.

4. Interrupcion natural.

   El usuario puede interrumpir mientras Atenea habla. Atenea debe pausar, clasificar la interrupcion, responder o guardar nota, y continuar si procede.

5. Notas sin romper flujo.

   El usuario puede guardar ideas mientras Atenea habla sin convertirlas aun en prompt. Las notas se conservan como transcripcion global y pueden enviarse despues.

6. Acciones sensibles confirmadas.

   Recuperar Apache, enviar emails, desplegar, cerrar incidencias o ejecutar acciones con impacto requieren confirmacion explicita.

7. Proveedor reemplazable.

   OpenAI, ElevenLabs u otro proveedor no deben quedar acoplados al dominio. Atenea necesita interfaces internas estables.

8. Persistencia.

   El foco, cursor de lectura, notas y eventos de voz deben sobrevivir a cierres de app, cortes de red o cambios de pantalla.

## Personalidad de voz

La voz objetivo es femenina, natural, cercana y profesional.

Estilo:

- habla de tu;
- espanol de Espana;
- frases claras;
- evita solemnidad;
- evita explicaciones largas si no se han pedido;
- confirma acciones con precision;
- distingue entre hecho, inferencia y propuesta.

Ejemplo:

```text
Estoy en fomasys, en la conversacion activa. Codex ya respondio. Te resumo primero: hay tres pasos siguientes. Primero ajustar permisos, segundo anadir test, tercero preparar deploy. Quieres que entre en detalle?
```

## Experiencia objetivo

```text
Usuario: Atenea, donde estas?
Atenea: Estoy en el proyecto fomasys, en la conversacion activa. Codex ya respondio y tengo una nota pendiente sin enviar.

Usuario: Atenea, dime cual es el siguiente paso.
Atenea: Te resumo. Hay tres pasos: revisar el error de permisos, anadir una prueba de regresion y preparar el deploy. Quieres detalle?

Usuario: Atenea, dame detalle.
Atenea: Empiezo por el primer punto...

Usuario interrumpe: Atenea, espera, no he entendido el segundo.
Atenea: Pauso. El segundo punto es anadir una prueba que reproduzca el fallo antes de tocar produccion. Cuando quieras sigo donde estaba.

Usuario: Sigue.
Atenea: Vuelvo al punto tres...
```
