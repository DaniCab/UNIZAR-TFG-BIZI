package es.unizar.iaaa.bizi;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileWriter;
import java.io.IOException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Map.Entry;

import org.apache.poi.hssf.usermodel.HSSFCell;
import org.apache.poi.hssf.usermodel.HSSFRow;
import org.apache.poi.hssf.usermodel.HSSFSheet;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.util.CellReference;
import org.json.simple.parser.ParseException;

/**
 * VERSION JHIPSTER
 * @author dani
 *
 */
public class TratamientoFicheros {

	private static Configuracion configuracion;
	private static String csvPath;
	private static String driverNameMysql, jdbcMysql, userMysql, passwordMysql;
	private static String pathCompletoCSV = null;
	
	public static void main(String[] args) throws IOException, ParseException {
		
		configuracion = new Configuracion();
		csvPath = configuracion.getCsvPath();

		// Comprobar que la carpeta donde se guardaran los CSV existe
		File csvDirectory = new File(csvPath);
		if (!csvDirectory.exists()) {
			// Si no existe se crea
			csvDirectory.mkdir();
		}
		
		driverNameMysql = configuracion.getDriverNameMysqlDB();
		jdbcMysql = configuracion.getJdbcMysqlConnector();
		String[] credentialMysql = configuracion.getCredentialMysqlDB().split(":");
		userMysql = credentialMysql[0];
		passwordMysql = credentialMysql[1];

		// Conectar a la base de datos

		try {
			Class.forName(driverNameMysql).newInstance();
		} catch (InstantiationException e) {
			e.printStackTrace();
		} catch (IllegalAccessException e) {
			e.printStackTrace();
		} catch (ClassNotFoundException e) {
			e.printStackTrace();
		}
		
		// Conectar con base de datos(tareas)
		try (Connection con = DriverManager.getConnection(jdbcMysql, userMysql, passwordMysql);) {
			Statement stmtSelect = con.createStatement();
			Statement stmtUpdate = con.createStatement();
			Statement stmtInsert = con.createStatement();

			// Obtener las ultimas X fechas introducidas en tareas.descargas
			String querySelect = "SELECT * FROM generarCSV WHERE estado=1 ORDER BY id DESC LIMIT 1";
			ResultSet rs = stmtSelect.executeQuery(querySelect);

			// Marcarlas como en proceso en tareas.descargas
			while (rs.next()) {
				// estado=2 => 'PROCESING"
				stmtUpdate.execute("UPDATE generarCSV SET estado=2 where id=" + rs.getInt("id"));
				String pathFicheroXLS = rs.getString("pathFicheroXLS");
				int id = rs.getInt("id");
				
				int existe = comprobarFicheroXLS(pathFicheroXLS);
				// Si el fichero XLS existe
				if(existe == 1) {
					int result = tratarXLS(pathFicheroXLS);
					// Si el fichero CSV se genera correctamente
					if(result == 1 && pathCompletoCSV!=null) {
						// estado = 3 => 'FINISHED'
						stmtUpdate.execute("UPDATE generarCSV SET estado=3 where id=" + rs.getInt("id"));
						
						// Variables para insertar valores en tabla insertarHadoop 
						String nombreTabla = "insertarHadoop";
						String tipo = "'" + rs.getString("tipo") + "'";
						String fechaFichero = "'" + rs.getString("fechaFichero") + "'";
						String path = "'" + pathCompletoCSV +"'";
						
						String insert = "INSERT INTO " + nombreTabla +
					            " (id, tipo, fechaFichero, pathFicheroCSV) " +
					            "VALUES (" + id + ", " + tipo + "," + fechaFichero + "," + path + ");";
						stmtInsert.execute(insert);
						
					} else {
						// estado = 1 => 'WAITING'
						stmtUpdate.execute("UPDATE generarCSV SET estado=1 where id=" + rs.getInt("id"));
					}
				} else { // Si el fichero XLS no existe
					// Marcar como error la tupla de la tablas.generarCSV estado = 4 => 'ERROR'
					stmtUpdate.execute("UPDATE generarCSV SET estado=4 where id=" + rs.getInt("id"));
					// Modificar el estado de la tupla en descargas, para que el fichero vuelva a ser descargado
					stmtUpdate.execute("UPDATE descargas SET estado=1 where id=" + rs.getInt("id"));
				}
				
			}

			con.close();
		} catch (SQLException e) {
			e.printStackTrace();
		}
		
	}
	
	/**
	 * Tratamiento de los ficheros xls para convertirlos en csv. A parte del
	 * contenido obtenido del xls, se le aniaden otros elementos para enriquecer el
	 * fichero.
	 * 
	 * @param pathFicheroXLS
	 *            Ruta completa del fichero xls que va a ser tratado.
	 * @param tipoDatosFichero
	 *            Tipo de fichero correspondiente a su contenido.
	 * @return
	 */
	private static int tratarXLS(String pathFicheroXLS) {
		try {
			FileInputStream excelFile = new FileInputStream(new File(pathFicheroXLS));

			HSSFWorkbook workbook = new HSSFWorkbook(excelFile);
			HSSFSheet sheet = workbook.getSheetAt(0);

			// Obtencion de datos relevantes para el fichero CSV
			Map<String, ArrayList<String>> datos = extraerDatosExcel(sheet);
			String fechaUso = extraerFechaDeUso(sheet);
			String fechaExtraccion = extraerFechaDeExtraccion(sheet);
			
			excelFile.close();
			// Nombre del fichero que se ha tratado
			String nombreFicheroXLS = pathFicheroXLS
					.substring(pathFicheroXLS.lastIndexOf(System.getProperty("file.separator")) + 1);

			// Generar fichero CSV a partir de los datos obtenidos
			pathCompletoCSV = crearCSV(datos, fechaUso, fechaExtraccion, nombreFicheroXLS);

			return 1;
		} catch (FileNotFoundException e) {
			// e.printStackTrace();
			return -1;
		} catch (IOException e) {
			// e.printStackTrace();
			return -1;
		}
	}
	
	/**
	 * Obtiene los datos recogidos en un fichero excel con una estructura definida.
	 * La estructura es la que viene dada por los ficheros descargados de la web clearchannel.
	 * @param sheet Hoja del fichero excel de la que se obtiene la informacion.
	 * @return Mapa clave/valor de los datos extraidos del fichero.
	 */
	private static Map<String, ArrayList<String>> extraerDatosExcel(HSSFSheet sheet) {

		Iterator<Row> iterator = sheet.iterator();
		String nombreEstacion = null;
		ArrayList<String> lista = null;
		Map<String, ArrayList<String>> datos = new HashMap<>();

		iteradores: while (iterator.hasNext()) {

			String datosFila = "";
			Row currentRow = iterator.next();
			Iterator<Cell> cellIterator = currentRow.iterator();

			// Recorrer cada celda de la fila.
			// Datos relevantes a partir de la fila 11 (Fila 12 en Excel)
			while (cellIterator.hasNext() && currentRow.getRowNum() >= 11) {

				HSSFCell currentCell = (HSSFCell) cellIterator.next();

				if (currentCell.getCellTypeEnum() == CellType.STRING) {
					// Si encuentra texto en la columna D ("Total Todos los
					// horarios")
					if (currentCell.getColumnIndex() == 3) {
						// Salir de ambos bucles. Fin de datos relevantes en
						// Excel
						break iteradores;
					} else {
						// Si encuentra texto en la columna B se trata del
						// nombre de la estacion
						if (currentCell.getColumnIndex() == 1) {
							nombreEstacion = currentCell.getStringCellValue().replaceAll(",", "").trim();
							lista = new ArrayList<>();
						} else {
							datosFila = datosFila.concat(currentCell.getStringCellValue().replace(",", ".") + ",");
						}
					}
				}
			}
			// Si no se trata de una fila vacia, se insertan los valores al Map
			if (!datosFila.equals("")) {
				lista.add(datosFila);
				datos.put(nombreEstacion, lista);
			}
		}
		return datos;
	}

	/**
	 * Obtiene la fecha que especifica de cuando es la informacion contenida en el fichero.
	 * @param sheet Hoja del fichero excel de la que se obtiene la informacion.
	 * @return fecha en formato dd/MM/yyyy
	 */
	private static String extraerFechaDeUso(HSSFSheet sheet) {
		String fechaDeUso = null;
		CellReference cellReference = new CellReference("C9");
		HSSFRow hssfrow = sheet.getRow(cellReference.getRow());
		HSSFCell hssfcell = hssfrow.getCell(cellReference.getCol());
		fechaDeUso = hssfcell.toString();
		String[] split = fechaDeUso.split(" ");
		fechaDeUso = split[split.length - 1];
		return fechaDeUso;
	}

	/**
	 * Obtiene la fecha que especifica de cuando se realizo la descarga del fichero.
	 * @param sheet Hoja del fichero excel de la que se obtiene la informacion.
	 * @return fecha en formato dd/MM/yyyy
	 */
	private static String extraerFechaDeExtraccion(HSSFSheet sheet) {
		String fechaDeExtraccion = null;
		CellReference cellReference = new CellReference("L3");
		HSSFRow hssfrow = sheet.getRow(cellReference.getRow());
		HSSFCell hssfcell = hssfrow.getCell(cellReference.getCol());
		fechaDeExtraccion = hssfcell.toString();
		return fechaDeExtraccion;
	}

	/**
	 * Genera fichero CSV.
	 * 
	 * @param datos
	 *            Mapa clava/valor con los datos extraidos del fichero xls.
	 * @param fechaUso
	 *            Fecha que especifica de cuando es la informacion contenida en el
	 *            fichero. En formato dd/MM/yyyy
	 * @param fechaExtraccion
	 *            Fecha que especifica de cuando se realizo la descarga del fichero.
	 *            En formato dd/MM/yyyy
	 * @param nombreFicheroXLS
	 *            Nombre que tiene el fichero xls de donde se obtuvieron los datos.
	 */
	private static String crearCSV(Map<String, ArrayList<String>> datos, String fechaUso, String fechaExtraccion,
			String nombreFicheroXLS) {

		// Prueba de generacion de nombre con messageFormat
		String nombreFicheroCSV = MessageFormat.format("{0}_{1}.csv",
				nombreFicheroXLS.substring(0, nombreFicheroXLS.lastIndexOf(".")), fechaExtraccion.replace("/", ""));

		String ruta = csvPath + System.getProperty("file.separator") + nombreFicheroCSV;

		try {
			File archivo = new File(ruta);
			BufferedWriter bw;

			if (!archivo.exists()) {
				bw = new BufferedWriter(new FileWriter(archivo));

				// Introducir cabeceras
				bw.write("nombreCompleto,idEstacion,nombreEstacion," + "fechaDeUso,intervaloDeTiempo,devolucionTotal,"
						+ "devolucionMedia,retiradasTotal,retiradasMedia,"
						+ "neto,total,fechaObtencionDatos,ficheroCSV," + "ficheroXLS\n");

				// Introducir datos
				for (Entry<String, ArrayList<String>> dato : datos.entrySet()) {
					// Extraer el id de la estacion
					String idEstacion = dato.getKey().split(" ")[0];
					// Extraer el nombre de la estacion
					String nombreEstacion = dato.getKey().substring(dato.getKey().indexOf("- ") + 1).trim();

					// Cambiar formato de las fechas a YYYY-MM-DD
					String[] fechaUsoSplit = fechaUso.split("/");
					String nuevaFechaUso = MessageFormat.format("{0}-{1}-{2}", fechaUsoSplit[2], fechaUsoSplit[1],
							fechaUsoSplit[0]);

					String[] fechaExtraccionSplit = fechaExtraccion.split("/");
					String nuevaFechaExtraccion = MessageFormat.format("{0}-{1}-{2}", fechaExtraccionSplit[2],
							fechaExtraccionSplit[1], fechaExtraccionSplit[0]);

					for (int i = 0; i < dato.getValue().size(); i++) {
						bw.write(dato.getKey() + "," + idEstacion + "," + nombreEstacion + "," + nuevaFechaUso + ","
								+ dato.getValue().get(i) + nuevaFechaExtraccion + "," + nombreFicheroCSV + ","
								+ nombreFicheroXLS + "\n");
					}
				}

				bw.close();
			}
			return ruta;
		} catch (IOException e) {
			e.printStackTrace();
			return null;
		}
	}
	
	private static int comprobarFicheroXLS(String pathCompletoXLS) {
		int result=-1;
		File fichero = new File(pathCompletoXLS);
	    if (fichero.exists()) {
	    	System.out.println("Existe");
	    	result = 1;
	    } else {
	    	System.out.println("No existe");
	    	result = -1;
	    }
		return result;
	}
	
}